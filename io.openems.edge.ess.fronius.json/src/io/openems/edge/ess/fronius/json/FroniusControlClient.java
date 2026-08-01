package io.openems.edge.ess.fronius.json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal client for the inofficial, undocumented Fronius GEN24/Symo-Hybrid
 * Web-Config-API (Digest-Auth), used only to WRITE battery control settings -
 * separate from the official, read-only Solar API v1
 * ({@code /solar_api/v1/...}) used elsewhere in this bundle.
 *
 * <p>
 * This is a from-scratch re-implementation of the authentication/endpoint
 * logic found in the open-source
 * <a href="https://github.com/muexxl/batcontrol">batcontrol</a> project,
 * which is the only known public documentation of this API. Fronius may
 * change this at any time with a firmware update - there is no guarantee of
 * stability. Use at your own risk on a live battery system; test carefully.
 *
 * <p>
 * Digest-Auth quirks specific to Fronius:
 * <ul>
 * <li>The nonce is sent back both on the initial 401 (as {@code
 * WWW-Authenticate}) and - non-standard - on subsequent successful
 * authenticated requests, under the header name {@code X-WWW-Authenticate}
 * or {@code X-Www-Authenticate} (capitalization is inconsistent between
 * firmware versions), to be used for the *next* request (rolling nonce).</li>
 * <li>Hash algorithm depends on firmware: MD5 below 1.38.6-1, SHA-256 from
 * 1.38.6-1 onwards.</li>
 * <li>Endpoint path prefix depends on firmware: {@code /config/...} below
 * 1.36, {@code /api/config/...} from 1.36 onwards.</li>
 * </ul>
 */
public class FroniusControlClient {

	private static final String REALM = "Webinterface area";

	/** Endpoint paths + hash algorithm for one firmware-version bracket. */
	private static final class ApiPaths {
		final String versionPath;
		final String configBatteriesPath;
		final String configTimeOfUsePath;
		final String algorithm;

		ApiPaths(String versionPath, String configBatteriesPath, String configTimeOfUsePath, String algorithm) {
			this.versionPath = versionPath;
			this.configBatteriesPath = configBatteriesPath;
			this.configTimeOfUsePath = configTimeOfUsePath;
			this.algorithm = algorithm;
		}
	}

	private static final ApiPaths PATHS_LEGACY = //
			new ApiPaths("/status/version", "/config/batteries", "/config/timeofuse", "MD5");
	private static final ApiPaths PATHS_MD5 = //
			new ApiPaths("/api/status/version", "/api/config/batteries", "/api/config/timeofuse", "MD5");
	private static final ApiPaths PATHS_SHA256 = //
			new ApiPaths("/api/status/version", "/api/config/batteries", "/api/config/timeofuse", "SHA-256");

	private final Logger log = LoggerFactory.getLogger(FroniusControlClient.class);
	private final String baseUrl;
	private final String user;
	private final String password;
	private final HttpClient httpClient;

	private ApiPaths paths;
	private String firmwareVersion;

	// Rolling Digest-Auth state - updated after every request.
	private volatile String nonce;
	private volatile String cnonce;
	private final AtomicInteger nc = new AtomicInteger(1);
	/**
	 * The exact {@code algorithm} token as sent by Fronius in its own
	 * {@code WWW-Authenticate}/{@code X-WWW-Authenticate} challenge (e.g.
	 * {@code "SHA256"} without hyphen - confirmed via live curl test against a
	 * GEN24 12.0 Plus on firmware 1.40.9-1, which differs from the RFC 7616
	 * token {@code "SHA-256"} we previously hardcoded per firmware bracket).
	 * Read dynamically from the server's own challenge and echoed back
	 * verbatim on the wire, instead of guessing from the firmware version.
	 * Falls back to {@link ApiPaths#algorithm} only until the first challenge
	 * has been received.
	 */
	private volatile String wireAlgorithm;
	/**
	 * The password-hash algorithm (for HA1 = user:realm:password) that has been
	 * confirmed to work, once found. Per the reference implementation
	 * (<a href="https://github.com/muexxl/batcontrol/blob/main/src/batcontrol/inverter/fronius.py">
	 * muexxl/batcontrol</a>), some Fronius firmwares hash HA1 with a *different*
	 * algorithm than the one advertised in {@code algorithm=} and used for HA2 /
	 * the final response hash - this must be discovered by trial. {@code null}
	 * until the first successful authenticated request.
	 */
	private volatile String cachedPasswordHashAlgorithm;

	public FroniusControlClient(String ip, String user, String password) {
		this.baseUrl = "http://" + ip;
		this.user = user;
		this.password = password;
		this.httpClient = HttpClient.newBuilder() //
				.connectTimeout(Duration.ofSeconds(10)) //
				.build();
		this.cnonce = randomHex(16);
	}

	/**
	 * Detects the firmware version and picks the matching endpoint paths and hash
	 * algorithm. Must be called once before any {@link #postConfig} call. Safe to
	 * call again to re-detect after e.g. a firmware update.
	 *
	 * @return the detected firmware version string
	 * @throws Exception on any communication or parsing error
	 */
	public synchronized String detectApiVersion() throws Exception {
		JsonObject result;
		try {
			result = this.sendUnauthenticated("GET", "/api/status/version");
			this.paths = null; // determined below based on version
		} catch (Exception e) {
			result = this.sendUnauthenticated("GET", "/status/version");
			this.paths = PATHS_LEGACY;
			this.firmwareVersion = getAsString(result, "swrevisions", "GEN24");
			return this.firmwareVersion;
		}
		this.firmwareVersion = getAsString(result, "swrevisions", "GEN24");
		this.paths = isVersionAtLeast(this.firmwareVersion, "1.38.6-1") ? PATHS_SHA256 : PATHS_MD5;
		return this.firmwareVersion;
	}

	/**
	 * Writes a JSON object of settings to {@code /api/config/batteries} (or the
	 * legacy path). Verifies via the response's {@code writeSuccess} array that
	 * every submitted key was actually applied by the device.
	 *
	 * @param settings the settings to write, e.g. {@code {"BAT_M0_SOC_MIN": 10}}
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	public void writeBatteryConfig(JsonObject settings) throws Exception {
		this.ensureApiDetected();
		var response = this.sendAuthenticated("POST", this.paths.configBatteriesPath, settings.toString());
		this.verifyWriteSuccess(response, settings.keySet());
	}

	/**
	 * One Time-of-Use schedule entry, always applying 00:00-23:59 on all
	 * weekdays.
	 *
	 * @param scheduleType {@code CHARGE_MIN}, {@code CHARGE_MAX},
	 *                         {@code DISCHARGE_MAX} or {@code DISCHARGE_MIN}
	 * @param powerWatt        the power limit/target in [W], {@code >= 0}
	 * @param active           whether the rule should be active
	 */
	public record TimeOfUseRule(String scheduleType, int powerWatt, boolean active) {
	}

	/**
	 * Writes a single-item Time-of-Use schedule (replacing any previously active
	 * schedule) that applies 00:00-23:59 on all weekdays, with {@code Active}
	 * set to {@code true}.
	 *
	 * @param scheduleType {@code CHARGE_MIN}, {@code CHARGE_MAX},
	 *                         {@code DISCHARGE_MAX} or {@code DISCHARGE_MIN}
	 * @param powerWatt        the power limit/target in [W], {@code >= 0}
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	public void writeTimeOfUse(String scheduleType, int powerWatt) throws Exception {
		this.writeTimeOfUse(scheduleType, powerWatt, true);
	}

	/**
	 * Same as {@link #writeTimeOfUse(String, int)}, but with an explicit
	 * {@code Active} flag - pass {@code false} to deactivate/withdraw a
	 * previously written rule (e.g. when giving control back to the device's own
	 * automatic logic on shutdown).
	 *
	 * @param scheduleType {@code CHARGE_MIN}, {@code CHARGE_MAX},
	 *                         {@code DISCHARGE_MAX} or {@code DISCHARGE_MIN}
	 * @param powerWatt        the power limit/target in [W], {@code >= 0}
	 * @param active           whether the rule should be active
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	public void writeTimeOfUse(String scheduleType, int powerWatt, boolean active) throws Exception {
		this.writeTimeOfUse(java.util.List.of(new TimeOfUseRule(scheduleType, powerWatt, active)));
	}

	/**
	 * Writes a Time-of-Use schedule consisting of one or more rules in a single
	 * POST call (replacing any previously active schedule), each applying
	 * 00:00-23:59 on all weekdays. Unlike two separate
	 * {@link #writeTimeOfUse(String, int, boolean)} calls - which would each
	 * replace the *entire* device schedule with just their own one item, since
	 * {@code POST /api/config/timeofuse} always replaces the complete
	 * Zeitsteuerung rather than merging - this allows e.g. a {@code CHARGE_MAX}
	 * ceiling and a {@code DISCHARGE_MIN} floor to be active on the device at
	 * the same time. Confirmed against a live device (Firefox Netzwerkanalyse of
	 * the GEN24's own Webinterface): it sends multiple differently-typed rules
	 * in exactly this array shape when e.g. both a discharge ceiling and a
	 * discharge floor are configured via the UI.
	 *
	 * @param rules the rules to write, in order; must not be empty
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	public void writeTimeOfUse(java.util.List<TimeOfUseRule> rules) throws Exception {
		this.ensureApiDetected();

		var weekdays = new JsonObject();
		for (var day : new String[] { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" }) {
			weekdays.addProperty(day, true);
		}
		var timeTable = new JsonObject();
		timeTable.addProperty("Start", "00:00");
		timeTable.addProperty("End", "23:59");

		var list = new JsonArray();
		for (var rule : rules) {
			var item = new JsonObject();
			item.addProperty("Active", rule.active());
			item.addProperty("Power", Math.max(0, rule.powerWatt()));
			item.addProperty("ScheduleType", rule.scheduleType());
			item.add("TimeTable", timeTable);
			item.add("Weekdays", weekdays);
			list.add(item);
		}
		var config = new JsonObject();
		config.add("timeofuse", list);

		var response = this.sendAuthenticated("POST", this.paths.configTimeOfUsePath, config.toString());
		this.verifyWriteSuccess(response, java.util.List.of("timeofuse"));
	}

	/**
	 * Same as {@link #writeTimeOfUse(java.util.List)}, but first writes the same
	 * rules with {@code Active=false} before writing them as given (typically
	 * {@code Active=true}). Workaround for an observed Fronius firmware quirk:
	 * changing just the {@code Power} value of an already-active rule via a
	 * normal POST was observed to be silently ignored by the device (HTTP 200,
	 * {@code writeSuccess} confirmed the write, but the persisted value did not
	 * change) - deactivating and reactivating the rule appears to force the
	 * device to properly re-apply it. Combined with the always-fresh-nonce
	 * behaviour in {@link #sendAuthenticated}, since both were suspected
	 * contributors - see readme.adoc. Not used for
	 * {@link #setTimeOfUse(JsonArray)} (backup restore on deactivate), which is
	 * a one-shot write, not a "change a running rule" scenario.
	 *
	 * @param rules the rules to write (as {@code Active=true} or whatever
	 *                  {@link TimeOfUseRule#active()} specifies); must not be
	 *                  empty
	 * @throws Exception on any communication, authentication or verification
	 *                        error - thrown if EITHER the deactivate or the
	 *                        reactivate POST fails; callers should treat this
	 *                        the same as a plain failed write (the device may be
	 *                        left with the rule deactivated in that case, same
	 *                        as any other failed write leaving the previous
	 *                        state in place)
	 */
	public void writeTimeOfUseForced(java.util.List<TimeOfUseRule> rules) throws Exception {
		var inactiveRules = rules.stream() //
				.map(rule -> new TimeOfUseRule(rule.scheduleType(), rule.powerWatt(), false)) //
				.toList();
		this.writeTimeOfUse(inactiveRules);
		this.writeTimeOfUse(rules);
	}

	/**
	 * Reads the currently active Time-of-Use schedule from the device (GET,
	 * authenticated) - mirrors the reference implementation's
	 * {@code get_time_of_use()}. Used to back up whatever schedule the user
	 * already had configured, before this bundle overwrites it with its own
	 * single-item rule, so it can be restored later.
	 *
	 * @return the raw {@code timeofuse} JSON array as currently stored on the
	 *         device (may be empty if none is configured)
	 * @throws Exception on any communication, authentication or parsing error
	 */
	public JsonArray getTimeOfUse() throws Exception {
		this.ensureApiDetected();
		var response = this.sendAuthenticated("GET", this.paths.configTimeOfUsePath, "");
		if (response.has("timeofuse") && response.get("timeofuse").isJsonArray()) {
			return response.getAsJsonArray("timeofuse");
		}
		return new JsonArray();
	}

	/**
	 * Writes an arbitrary, already-complete Time-of-Use schedule as-is
	 * (replacing whatever is currently configured) - unlike
	 * {@link #writeTimeOfUse(String, int, boolean)}, which always constructs
	 * exactly one 00:00-23:59/all-weekdays rule. Used only to restore a
	 * previously backed-up schedule (see {@link #getTimeOfUse()}), e.g. the
	 * user's own rules that existed before this bundle took over control.
	 *
	 * @param items the raw {@code timeofuse} JSON array to write back verbatim
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	public void setTimeOfUse(JsonArray items) throws Exception {
		this.ensureApiDetected();
		var config = new JsonObject();
		config.add("timeofuse", items);
		var response = this.sendAuthenticated("POST", this.paths.configTimeOfUsePath, config.toString());
		this.verifyWriteSuccess(response, java.util.List.of("timeofuse"));
	}

	/**
	 * Returns the firmware version detected by {@link #detectApiVersion()}, or
	 * {@code null} if it has not run yet.
	 *
	 * @return the firmware version string, or {@code null}
	 */
	public String getFirmwareVersion() {
		return this.firmwareVersion;
	}

	private void ensureApiDetected() throws Exception {
		if (this.paths == null) {
			this.detectApiVersion();
		}
	}

	private void verifyWriteSuccess(JsonObject response, Iterable<String> expectedKeys) throws Exception {
		JsonArray writeSuccess = response.has("writeSuccess") && response.get("writeSuccess").isJsonArray() //
				? response.getAsJsonArray("writeSuccess")
				: new JsonArray();
		var succeeded = new java.util.HashSet<String>();
		writeSuccess.forEach(e -> succeeded.add(e.getAsString()));
		for (var key : expectedKeys) {
			if (!succeeded.contains(key)) {
				throw new Exception("Fronius hat den Schreibvorgang fuer '" + key
						+ "' nicht bestaetigt (writeSuccess enthaelt es nicht). Antwort: " + response);
			}
		}
	}

	// -------------------------------------------------------------------------
	// Low-level HTTP + Digest-Auth
	// -------------------------------------------------------------------------

	private JsonObject sendUnauthenticated(String method, String path) throws Exception {
		var request = HttpRequest.newBuilder() //
				.uri(URI.create(this.baseUrl + path)) //
				.timeout(Duration.ofSeconds(15)) //
				.method(method, BodyPublishers.noBody()) //
				.build();
		var response = this.httpClient.send(request, BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new Exception(
					"GET " + path + " fehlgeschlagen mit HTTP " + response.statusCode() + ": " + response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	/**
	 * Sends an authenticated request, performing the Digest-Auth handshake
	 * (priming request to obtain a nonce) if none is cached yet, and retrying
	 * once more on 401/403 with a fresh nonce.
	 *
	 * @param method   the HTTP method, e.g. {@code "POST"}
	 * @param path     the request path
	 * @param jsonBody the JSON request body, or an empty string for a body-less
	 *                     request
	 * @return the parsed JSON response body
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	private JsonObject sendAuthenticated(String method, String path, String jsonBody) throws Exception {
		// Always fetch a fresh nonce, rather than reusing the "rolling nonce"
		// echoed back by the previous response (this.nonce != null check removed
		// on purpose) - a change to the Power value of an already-active
		// Time-of-Use rule was observed to be silently ignored by the device
		// (HTTP 200, writeSuccess confirmed, persisted value unchanged), suspected
		// to be a stale/reused-nonce replay on the device side (undocumented API,
		// not confirmed against Fronius sources - see readme.adoc). The extra
		// round-trip this costs is negligible given how infrequently writes
		// actually happen (rate-limited to at most every minWriteIntervalSeconds).
		this.primeNonce(method, path);
		var candidates = this.passwordHashCandidates();
		var maxAttempts = this.cachedPasswordHashAlgorithm != null ? 2 : Math.max(2, candidates.length);
		for (var attempt = 0; attempt < maxAttempts; attempt++) {
			var pwAlg = this.cachedPasswordHashAlgorithm != null //
					? this.cachedPasswordHashAlgorithm
					: candidates[Math.min(attempt, candidates.length - 1)];
			var response = this.sendOnce(method, path, jsonBody, true, pwAlg);
			this.updateAuthStateFromResponse(response);
			if (response.statusCode() == 200) {
				// Merken, welches HA1-Hash-Verfahren funktioniert hat, um kuenftige
				// Aufrufe nicht mehr durchprobieren zu muessen.
				this.cachedPasswordHashAlgorithm = pwAlg;
				return JsonParser.parseString(response.body()).getAsJsonObject();
			}
			if (response.statusCode() == 401 || response.statusCode() == 403) {
				this.log.debug(
						"Fronius Control-API antwortete mit {} (Passwort-Hash-Verfahren '{}' versucht) - Nonce "
								+ "erneuert, versuche erneut",
						response.statusCode(), pwAlg);
				continue;
			}
			throw new Exception(
					method + " " + path + " fehlgeschlagen mit HTTP " + response.statusCode() + ": " + response.body());
		}
		throw new Exception(method + " " + path + " fehlgeschlagen: Authentifizierung nach " + maxAttempts
				+ " Versuchen (Passwort-Hash-Verfahren " + String.join(", ", candidates)
				+ ") abgelehnt (falscher Benutzername/Passwort des Service-Accounts?)");
	}

	/**
	 * Candidate HA1 password-hash algorithms to try, in order, mirroring the
	 * reference implementation's {@code usable_password_hash_methods}.
	 *
	 * @return the candidate algorithm names, in the order they should be tried
	 */
	private String[] passwordHashCandidates() {
		var wireAlg = this.wireAlgorithm != null ? this.wireAlgorithm : this.paths.algorithm;
		if ("MD5".equalsIgnoreCase(wireAlg)) {
			return new String[] { "MD5" };
		}
		return new String[] { "SHA-256", "MD5", "MD5" };
	}

	/**
	 * Sends one request without Authorization header, purely to obtain a nonce.
	 *
	 * @param method the HTTP method, e.g. {@code "POST"}
	 * @param path   the request path
	 * @throws Exception on any communication error, or if no nonce was returned
	 */
	private void primeNonce(String method, String path) throws Exception {
		var response = this.sendOnce(method, path, "", false, null);
		this.updateAuthStateFromResponse(response);
		if (this.nonce == null) {
			throw new Exception(
					"Fronius hat auf " + path + " keinen Digest-Auth-Nonce zurueckgegeben (HTTP " + response.statusCode()
							+ ", Header: " + response.headers().map() + ") - Endpunkt/Firmware evtl. nicht unterstuetzt.");
		}
	}

	private HttpResponse<String> sendOnce(String method, String path, String jsonBody, boolean withAuth,
			String passwordHashAlgorithm) throws Exception {
		var builder = HttpRequest.newBuilder() //
				.uri(URI.create(this.baseUrl + path)) //
				.timeout(Duration.ofSeconds(15));
		if (jsonBody == null || jsonBody.isEmpty()) {
			builder.method(method, BodyPublishers.noBody());
		} else {
			builder.header("Content-Type", "application/json");
			builder.method(method, BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
		}
		if (withAuth) {
			builder.header("Authorization", this.buildDigestHeader(method, path, passwordHashAlgorithm));
		}
		return this.httpClient.send(builder.build(), BodyHandlers.ofString());
	}

	/**
	 * Builds the {@code Authorization: Digest ...} header value for one request.
	 *
	 * @param method                        the HTTP method, e.g. {@code "POST"}
	 * @param path                          the request path
	 * @param passwordHashAlgorithmOverride Java {@link MessageDigest} name to use
	 *                                          for HA1 ({@code user:realm:password})
	 *                                          specifically - may differ from the
	 *                                          wire {@code algorithm=} token used for
	 *                                          HA2/response (see
	 *                                          {@link #cachedPasswordHashAlgorithm}).
	 * @return the header value
	 * @throws NoSuchAlgorithmException if the resolved hash algorithm is not
	 *                                       available on this JVM
	 */
	private String buildDigestHeader(String method, String path, String passwordHashAlgorithmOverride)
			throws NoSuchAlgorithmException {
		var ncValue = String.format("%08d", this.nc.getAndIncrement());
		// Wire token: whatever Fronius itself sent in its challenge, if we've
		// already seen one; otherwise fall back to our firmware-bracket guess.
		var wireAlg = this.wireAlgorithm != null ? this.wireAlgorithm : this.paths.algorithm;
		// Java's MessageDigest needs the RFC-style hyphenated name regardless
		// of how Fronius spells it on the wire (e.g. "SHA256" -> "SHA-256").
		var javaAlg = toJavaAlgorithmName(wireAlg);
		var ha1Alg = passwordHashAlgorithmOverride != null ? passwordHashAlgorithmOverride : javaAlg;
		var a1 = this.user + ":" + REALM + ":" + this.password;
		var a2 = method + ":" + path;
		var ha1 = hash(a1, ha1Alg);
		var ha2 = hash(a2, javaAlg);
		var noncebits = this.nonce + ":" + ncValue + ":" + this.cnonce + ":auth:" + ha2;
		var response = hash(ha1 + ":" + noncebits, javaAlg);
		return "Digest username=\"" + this.user + "\", realm=\"" + REALM + "\", nonce=\"" + this.nonce + "\", uri=\""
				+ path + "\", algorithm=\"" + wireAlg + "\", qop=auth, nc=" + ncValue + ", cnonce=\"" + this.cnonce
				+ "\", response=\"" + response + "\"";
	}

	/**
	 * Maps a wire-format Digest algorithm token to the name Java's
	 * {@link MessageDigest} expects.
	 *
	 * @param wireAlg the algorithm token as sent by Fronius, e.g. {@code "SHA256"}
	 * @return the equivalent Java {@link MessageDigest} algorithm name
	 */
	private static String toJavaAlgorithmName(String wireAlg) {
		return switch (wireAlg.toUpperCase(java.util.Locale.ROOT)) {
		case "SHA256", "SHA-256" -> "SHA-256";
		case "MD5" -> "MD5";
		default -> wireAlg;
		};
	}

	/**
	 * Parses {@code WWW-Authenticate} (on 401) or the non-standard
	 * {@code X-WWW-Authenticate}/{@code X-Www-Authenticate} (on subsequent 200
	 * responses, rolling nonce) and updates the cached nonce/cnonce.
	 *
	 * @param response the HTTP response to read auth headers from
	 */
	private void updateAuthStateFromResponse(HttpResponse<String> response) {
		var headers = response.headers();
		String authString = headers.firstValue("WWW-Authenticate") //
				.or(() -> headers.firstValue("X-WWW-Authenticate")) //
				.or(() -> headers.firstValue("X-Www-Authenticate")) //
				.or(() -> headers.firstValue("Authentication-Info")) //
				.orElse(null);
		if (authString == null) {
			return;
		}
		var parsed = parseAuthHeader(authString);
		if (parsed.containsKey("nonce")) {
			this.nonce = parsed.get("nonce");
		}
		if (parsed.containsKey("cnonce")) {
			this.cnonce = parsed.get("cnonce");
		}
		if (parsed.containsKey("algorithm")) {
			// Use Fronius' own literal token on the wire (e.g. "SHA256", no
			// hyphen) instead of the RFC-style name we assumed per firmware
			// bracket - avoids an algorithm-mismatch rejection on the digest
			// response even though the nonce itself was obtained successfully.
			this.wireAlgorithm = parsed.get("algorithm");
		}
		// nc is tracked locally by us (incremented per request), Fronius does not
		// need to tell us its value - but if provided, resync to be safe.
		if (parsed.containsKey("nc")) {
			try {
				this.nc.set(Integer.parseInt(parsed.get("nc")) + 1);
			} catch (NumberFormatException ignored) {
				// keep local counter
			}
		} else {
			// Fronius' eigene Challenge (per curl bestaetigt) liefert gar kein
			// "nc" zurueck - jede Antwort mit frischer Nonce startet die
			// nc-Sequenz neu bei 1, statt eines global weiterlaufenden Zaehlers,
			// der nicht mehr zu dieser brandneuen Nonce passt.
			this.nc.set(1);
		}
	}

	private static Map<String, String> parseAuthHeader(String authString) {
		var result = new HashMap<String, String>();
		for (var part : authString.replace("Digest ", "").replace("\"", "").split(",")) {
			var kv = part.trim().split("=", 2);
			if (kv.length == 2) {
				result.put(kv[0].trim(), kv[1].trim());
			}
		}
		return result;
	}

	private static String hash(String input, String algorithm) throws NoSuchAlgorithmException {
		var digest = MessageDigest.getInstance(algorithm);
		var bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
		var sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private static String randomHex(int bytes) {
		var random = new java.security.SecureRandom();
		var buffer = new byte[bytes];
		random.nextBytes(buffer);
		var sb = new StringBuilder();
		for (byte b : buffer) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	/**
	 * Very small, dependency-free version comparator for "1.38.6-1" style
	 * strings.
	 *
	 * @param version    the version to check
	 * @param minVersion the minimum required version
	 * @return {@code true} if {@code version} is greater than or equal to
	 *         {@code minVersion}
	 */
	private static boolean isVersionAtLeast(String version, String minVersion) {
		try {
			var a = version.replace("-", ".").split("\\.");
			var b = minVersion.replace("-", ".").split("\\.");
			for (var i = 0; i < Math.max(a.length, b.length); i++) {
				var ai = i < a.length ? Integer.parseInt(a[i]) : 0;
				var bi = i < b.length ? Integer.parseInt(b[i]) : 0;
				if (ai != bi) {
					return ai > bi;
				}
			}
			return true;
		} catch (NumberFormatException e) {
			// If the version string can't be parsed, be conservative and assume the
			// newer SHA-256 based API (all current firmware as of 2026).
			return true;
		}
	}

	private static String getAsString(JsonObject root, String... path) {
		JsonElement current = root;
		for (var segment : path) {
			if (current == null || !current.isJsonObject()) {
				return null;
			}
			current = current.getAsJsonObject().get(segment);
		}
		return current == null || current.isJsonNull() ? null : current.getAsString();
	}
}

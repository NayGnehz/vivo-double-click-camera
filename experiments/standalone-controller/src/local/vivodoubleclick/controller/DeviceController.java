package local.vivodoubleclick.controller;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Fixed 1.0 operations over an authenticated ADB connection; never executes as app UID. */
public final class DeviceController {
    public interface Shell {
        /** The runner must bound output and close only this command's ADB stream on timeout. */
        Result run(String command, int timeoutSeconds) throws Exception;
    }

    public static final class Result {
        public final int exitCode;
        public final String output;
        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private static final String DIR = "/data/local/tmp/vivo-double-click";
    private static final String SCRIPT_HASH = "9a1f13b99420405b0fda63272b96bfd13965995d55315a17aaab69f4cf6bdcdf";
    private static final String JAR_HASH = "6eca8617ccaf7236de286b55489e0bf9faeb0648e619c32afdca6ba8b3090f51";
    private static final String LOG_MARKER = "__VDC_RECENT_LOG__";
    private final Context context;
    private final Shell shell;

    // All remote names and shell programs are fixed; no path or shell text comes from UI input.
    private static final String GUARDS =
            "set -u\n"
            + "fail() { printf 'ERROR: %s\\n' \"$1\" >&2; exit 1; }\n"
            + "[ \"$(id -u)\" = 2000 ] || fail 'ADB must run as shell UID 2000; root is not accepted'\n"
            + "D=" + DIR + "\n"
            + "[ \"$(readlink -f /data/local/tmp)\" = /data/local/tmp ] || fail 'Redirected parent directory'\n"
            + "check_file() {\n"
            + "  [ ! -L \"$1\" ] || fail 'Refusing symbolic link in service files'\n"
            + "  if [ -e \"$1\" ]; then\n"
            + "    [ -f \"$1\" ] || fail 'Service path is not a regular file'\n"
            + "    [ \"$(stat -c %u \"$1\")\" = 2000 ] || fail 'Service file is not owned by shell'\n"
            + "  fi\n"
            + "}\n"
            + "check_layout() {\n"
            + "  [ ! -L \"$D\" ] || fail 'Refusing symbolic-link service directory'\n"
            + "  if [ -e \"$D\" ]; then\n"
            + "    [ -d \"$D\" ] || fail 'Service directory is not a directory'\n"
            + "    [ \"$(stat -c %u \"$D\")\" = 2000 ] || fail 'Service directory is not owned by shell'\n"
            + "  fi\n"
            + "  for n in device-control.sh vivo-camera.jar service.pid service.pid.tmp service.log service.lock stop.request device-control.sh.new vivo-camera.jar.new; do\n"
            + "    check_file \"$D/$n\"\n"
            + "  done\n"
            + "}\n"
            + "hash_is() {\n"
            + "  [ -f \"$1\" ] || return 1\n"
            + "  digest=$(sha256sum \"$1\" | cut -d ' ' -f 1) || fail 'SHA-256 check failed'\n"
            + "  [ \"$digest\" = \"$2\" ]\n"
            + "}\n"
            + "verify_script() {\n"
            + "  hash_is \"$D/device-control.sh\" " + SCRIPT_HASH
            + " || fail 'Control script is not frozen version 1.0; no script executed'\n"
            + "}\n"
            + "check_layout\n";

    public DeviceController(Context context, Shell shell) {
        if (context == null || shell == null) throw new IllegalArgumentException("Context and Shell are required");
        this.context = context;
        this.shell = shell;
    }

    /** An existing, verified running daemon is returned unchanged, with no deployment/restart. */
    public synchronized JSONObject start() {
        try {
            Snapshot state = inspect();
            if (state.running()) return response(state, true, "Already running; kept unchanged");
            if (!state.scriptInstalled) {
                deploy(false);
                state = inspect();
                if (state.running()) return response(state, true, "Restored missing control script; running daemon kept unchanged");
            }
            if (!"STOPPED".equals(state.state)) throw new IOException("Cannot verify a safe stopped state");
            if (!state.jarVerified) {
                Result stopped = control("stop");
                Snapshot afterStop = inspect();
                if (stopped.exitCode != 0 || !"STOPPED".equals(afterStop.state))
                    return response(afterStop, false, "JAR unchanged: clean stop could not be verified. " + stopped.output);
                deploy(true);
            }
            Result started = control("start");
            Snapshot after = inspect();
            return response(after, after.running(), started.output);
        } catch (Exception error) {
            return failure(error);
        }
    }

    public synchronized JSONObject stop() {
        try {
            Snapshot before = inspect();
            if (!before.scriptInstalled)
                return response(before, "STOPPED".equals(before.state),
                        "STOPPED".equals(before.state) ? "Service is not installed" : "Control script missing; no process was signalled");
            Result stopped = control("stop");
            Snapshot after = inspect();
            return response(after, stopped.exitCode == 0 && "STOPPED".equals(after.state), stopped.output);
        } catch (Exception error) {
            return failure(error);
        }
    }

    public synchronized JSONObject status() {
        try {
            Snapshot state = inspect();
            return response(state, !"UNKNOWN".equals(state.state), "Status checked");
        } catch (Exception error) {
            return failure(error);
        }
    }

    private Snapshot inspect() throws Exception {
        String body = GUARDS
                + "script=missing; jar=missing; state=STOPPED\n"
                + "if [ -f \"$D/device-control.sh\" ]; then\n"
                + "  verify_script; script=verified\n"
                + "  status_text=$(timeout 5 /system/bin/sh \"$D/device-control.sh\" status 2>&1); status_code=$?\n"
                + "  if [ \"$status_code\" = 0 ] && printf '%s\\n' \"$status_text\" | grep -E -q '^RUNNING: PID [0-9]+; log /data/local/tmp/vivo-double-click/service\\.log$'; then\n"
                + "    state=RUNNING\n"
                + "  elif [ \"$status_code\" = 1 ] && printf '%s\\n' \"$status_text\" | grep -x -q STOPPED; then\n"
                + "    state=STOPPED\n"
                + "  else fail \"Cannot establish daemon status: $status_text\"; fi\n"
                + "elif [ -e \"$D/service.pid\" ]; then state=UNKNOWN; fi\n"
                + "if [ -f \"$D/vivo-camera.jar\" ]; then\n"
                + "  if hash_is \"$D/vivo-camera.jar\" " + JAR_HASH + "; then jar=verified; else jar=mismatch; fi\n"
                + "fi\n"
                + "printf 'UID=2000\\nSTATE=%s\\nSCRIPT=%s\\nJAR=%s\\n' \"$state\" \"$script\" \"$jar\"\n"
                + "printf '" + LOG_MARKER + "\\n'\n"
                + "if [ -f \"$D/service.log\" ]; then tail -c 16384 \"$D/service.log\" | tail -n 30; fi\n";
        Result result = run(body, 8);
        if (result.exitCode != 0) throw new IOException(limit(result.output));
        String normalized = result.output.replace("\r\n", "\n");
        String delimiter = "\n" + LOG_MARKER;
        int split = normalized.indexOf(delimiter);
        if (split < 0) throw new IOException("Missing fixed status response marker");
        int logStart = split + delimiter.length();
        if (logStart < normalized.length()) {
            if (normalized.charAt(logStart) != '\n') throw new IOException("Malformed log response marker");
            logStart++;
        }
        String headers = normalized.substring(0, split);
        if (!"2000".equals(value(headers, "UID"))) throw new IOException("ADB did not confirm shell UID 2000");
        String state = value(headers, "STATE");
        if (!("RUNNING".equals(state) || "STOPPED".equals(state) || "UNKNOWN".equals(state)))
            throw new IOException("Invalid daemon status response");
        return new Snapshot(state, "verified".equals(value(headers, "SCRIPT")),
                "verified".equals(value(headers, "JAR")), normalized.substring(logStart));
    }

    private Result control(String action) throws Exception {
        final int remoteTimeout;
        if ("start".equals(action)) remoteTimeout = 30;
        else if ("stop".equals(action)) remoteTimeout = 10;
        else if ("status".equals(action)) remoteTimeout = 5;
        else throw new IllegalArgumentException("Unsupported fixed control action");
        return run(GUARDS + "verify_script\ntimeout " + remoteTimeout
                + " /system/bin/sh \"$D/device-control.sh\" " + action + "\n", remoteTimeout + 4);
    }

    private void deploy(boolean jar) throws Exception {
        String name = jar ? "vivo-camera.jar" : "device-control.sh";
        String hash = jar ? JAR_HASH : SCRIPT_HASH;
        byte[] asset = readAsset(name);
        if (!hash.equals(sha256(asset))) throw new IOException("Bundled frozen 1.0 checksum mismatch: " + name);
        String encoded = Base64.encodeToString(asset, Base64.NO_WRAP);
        // Base64 is generated from verified bytes, never from a user-supplied command or path.
        String body = GUARDS
                + "umask 077\n"
                + "if [ ! -d \"$D\" ]; then mkdir \"$D\" || fail 'Cannot create service directory'; chmod 700 \"$D\" || fail 'Cannot protect service directory'; fi\n"
                + "check_layout\n";
        if (!jar) {
            body += "if [ -e \"$D/device-control.sh\" ]; then verify_script; printf 'Control script already present\\n'; exit 0; fi\n";
        } else {
            body += requireStopped();
        }
        body += "T=\"$D/" + name + ".new\"\n"
                + "rm -f \"$T\" || fail 'Cannot clear previous staging file'\n"
                + "trap 'rm -f \"$T\"' 0\n"
                + "printf '%s' '" + encoded + "' | base64 -d > \"$T\" || fail 'Base64 deployment failed'\n"
                + "hash_is \"$T\" " + hash + " || fail 'Staged asset checksum mismatch'\n"
                + "chmod " + (jar ? "400" : "700") + " \"$T\" || fail 'Cannot set staged file permissions'\n"
                + "check_layout\n";
        if (jar) body += requireStopped();
        else body += "[ ! -e \"$D/device-control.sh\" ] || fail 'Control script appeared during deployment; unchanged'\n";
        body += "mv -f \"$T\" \"$D/" + name + "\" || fail 'Atomic deployment rename failed'\n"
                + "trap - 0\nprintf 'DEPLOYED: " + name + "\\n'\n";
        Result result = run(body, 18);
        if (result.exitCode != 0) throw new IOException(limit(result.output));
    }

    private static String requireStopped() {
        return "verify_script\n"
                + "stopped_text=$(timeout 5 /system/bin/sh \"$D/device-control.sh\" status 2>&1); stopped_code=$?\n"
                + "[ \"$stopped_code\" = 1 ] && printf '%s\\n' \"$stopped_text\" | grep -x -q STOPPED"
                + " || fail 'Daemon is not verified stopped; no artifact replaced'\n";
    }

    private Result run(String body, int timeoutSeconds) throws Exception {
        // A nested shell contains exit/fail so the runner can append its own exit-code marker.
        return shell.run("/system/bin/sh -c " + quote(body), timeoutSeconds);
    }

    private JSONObject failure(Exception error) {
        Snapshot state;
        try { state = inspect(); } catch (Exception ignored) { state = new Snapshot("UNKNOWN", false, false, ""); }
        return response(state, false, error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static JSONObject response(Snapshot state, boolean ok, String message) {
        try {
            return new JSONObject().put("ok", ok).put("state", state.state)
                    .put("running", "UNKNOWN".equals(state.state) ? JSONObject.NULL : state.running())
                    .put("installed", state.scriptInstalled && state.jarVerified)
                    .put("message", limit(message)).put("log", state.log);
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not encode controller response", impossible);
        }
    }

    private static final class Snapshot {
        final String state;
        final boolean scriptInstalled;
        final boolean jarVerified;
        final String log;
        Snapshot(String state, boolean scriptInstalled, boolean jarVerified, String log) {
            this.state = state;
            this.scriptInstalled = scriptInstalled;
            this.jarVerified = jarVerified;
            this.log = log;
        }
        boolean running() { return "RUNNING".equals(state); }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > 4 * 1024 * 1024) throw new IOException("Bundled asset exceeds 4 MiB");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            text.append("0123456789abcdef".charAt((value >>> 4) & 15));
            text.append("0123456789abcdef".charAt(value & 15));
        }
        return text.toString();
    }

    private static String value(String headers, String key) {
        String prefix = key + "=";
        for (String line : headers.split("\n")) if (line.startsWith(prefix)) return line.substring(prefix.length());
        return "";
    }

    private static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }
    private static String limit(String text) {
        if (text == null) return "";
        return text.length() <= 32768 ? text : text.substring(0, 32768) + "\n[output truncated]";
    }
}

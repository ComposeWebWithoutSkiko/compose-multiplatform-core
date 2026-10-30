const {execFileSync} = require("node:child_process");

/** Runs [script], an AppleScript; a failure is reported as a warning, as [what] failed. */
function runAppleScript(script, what) {
    try {
        execFileSync("osascript", ["-e", script], {stdio: "ignore"});
    } catch (error) {
        console.warn(`Couldn't ${what}: ${error.message}`);
    }
}

/**
 * A Safari launcher that opens the page in a window of the running Safari (launching it if needed)
 * and closes it when Karma kills the launcher or exits (also when it crashes). Not a process of its
 * own, like karma-safari-launcher: the Safari executable started by hand is an instance of its own,
 * which restores the tabs of the previous one, outlives Karma's kill, and isn't the instance
 * AppleScript controls.
 */
function SafariWindow(baseBrowserDecorator) {
    baseBrowserDecorator(this);
    let pageUrl = null;
    // The window opened for the page: Safari doesn't close the last tab of a window, but the window.
    const closePage = () => {
        if (pageUrl === null) return;
        runAppleScript(
            `tell application "Safari" to close (every window whose URL of current tab starts with "${pageUrl}")`,
            `close the Safari window of ${pageUrl}`,
        );
    };
    this._start = (url) => {
        pageUrl = url;
        runAppleScript(
            `tell application "Safari"\nactivate\nmake new document with properties {URL:"${url}"}\nend tell`,
            `open ${url} in Safari`,
        );
    };
    this.on("kill", (done) => {
        closePage();
        done();
    });
    process.on("exit", closePage);
}

SafariWindow.prototype = {name: "Safari"};
SafariWindow.$inject = ["baseBrowserDecorator"];

function configLaunchers(config) {
    // A port of its own for every run: the Karma client of a page left by an earlier run reconnects
    // to that run's port, so it can't join this run as another browser.
    config.port = 9876 + Math.floor(Math.random() * 1000);
    config.plugins.push({"launcher:SafariWindow": ["type", SafariWindow]});
    config.customLaunchers = {
        ChromeForComposeTests: {
            base: "Chrome",
            flags: [
                "--no-sandbox",
                "--disable-search-engine-choice-screen",

                // "CI-stabilizer" :D - otherwise webgl2 context is null:
                "--disable-setuid-sandbox",
                "--enable-webgl",
                "--ignore-gpu-blocklist",
                "--in-process-gpu",

                // Keep rendering (and firing requestAnimationFrame) when the test window is behind
                // other windows: the tests wait for animation frames.
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-background-timer-throttling",

                // gc(), for the tests that check what isn't kept alive.
                "--js-flags=--expose-gc"
            ]
        },
        ChromiumForComposeTests: {
            base: "Chromium"
        },
        FirefoxForComposeTests: {
            base: "Firefox",
            prefs: {
                'dom.w3c_touch_events.enabled': 1,

                // https://firefox-source-docs.mozilla.org/toolkit/components/telemetry/internals/preferences.html
                // Records the version of the policy notified to the user. This preference is also used on Android, used in tests, it allows to skip the notification check.
                'datareporting.policy.dataSubmissionPolicyBypassNotification': true,

                // The properties to stabilize the tests running in Firefox on CI:
                'layout.frame_rate': 30, // less CPU-heavy
                'dom.timeout.background_throttling_max_budget': -1,
                'dom.min_background_timeout_value': 4,
                'privacy.reduceTimerPrecision': false,
                'dom.suspend_inactive.enabled': false
            }
        },
        SafariForComposeTests: {
            base: "SafariWindow"
        }
    }

    config.browsers = [];
    if (process.env["jetbrains.androidx.web.tests.enableChrome"]) {
        config.browsers.push("ChromeForComposeTests");
    }
    if (process.env["jetbrains.androidx.web.tests.enableChromium"]) {
        config.browsers.push("ChromiumForComposeTests");
    }
    if (process.env["jetbrains.androidx.web.tests.enableFirefox"]) {
        config.browsers.push("FirefoxForComposeTests");
    }
    if (process.env["jetbrains.androidx.web.tests.enableSafari"]) {
        config.browsers.push("SafariForComposeTests");
    }

    console.log("Browsers: " + config.browsers);
}

exports.configLaunchers = configLaunchers;
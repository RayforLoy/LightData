import { t } from "./i18n.js";
// Use a small, fixed sampling grid on the existing analysis frame. The four
// samples per cell damp sensor noise while keeping local subject motion visible.
export class FrameMotion {
  reset() {
    this.previous = this.anchor = null;
    this.still = false;
  }

  observe(image, time) {
    const cols = Math.min(image.width, 40), rows = Math.min(image.height, 30);
    const grid = new Uint8Array(cols * rows);
    const { width, height, data } = image;
    for (let row = 0; row < rows; row++) {
      for (let col = 0; col < cols; col++) {
        let total = 0;
        for (let q = 0; q < 4; q++) {
          const x = Math.floor((col + ((q & 1) ? .75 : .25)) * width / cols);
          const y = Math.floor((row + ((q & 2) ? .75 : .25)) * height / rows);
          const i = 4 * (y * width + x);
          total += 54 * data[i] + 183 * data[i + 1] + 19 * data[i + 2];
        }
        grid[row * cols + col] = Math.round(total / 1024);
      }
    }
    if (!this.previous || time <= this.lastTime || time - this.lastTime > 1000 ||
        this.width !== width || this.height !== height) {
      this.still = false;
      this.quietSince = time;
      this.anchor = grid;
    } else {
      // Compare against the start of the quiet period too, so a slow pan cannot
      // qualify as stationary by moving only slightly between adjacent frames.
      let total = 0, changed = 0;
      for (let i = 0; i < grid.length; i++) {
        const difference = Math.max(Math.abs(grid[i] - this.previous[i]),
          Math.abs(grid[i] - this.anchor[i]));
        total += difference;
        if (difference > 10) changed++;
      }
      if (total / grid.length > (this.still ? 4 : 2.5) ||
          changed / grid.length > (this.still ? .035 : .02)) {
        this.still = false;
        this.quietSince = time;
        this.anchor = grid;
      } else if (time - this.quietSince >= 800) this.still = true;
    }
    this.width = width;
    this.height = height;
    this.lastTime = time;
    this.previous = grid;
    return this.still;
  }
}

// Fractional source rectangle and destination inset preserve transparent areas
// beyond the captured field. Resolution never exceeds available source density.
export function captureGeometry(width, height, source, target, scale = 1) {
  const wide = source && target ? width * target.x / source.x : width;
  const high = source && target ? height * target.y / source.y : height;
  if (![wide, high, scale].every(v => Number.isFinite(v) && v > 0)) return null;
  const ratio = Math.min(1, Math.max(width, height) / Math.max(wide, high)) * Math.min(1, scale);
  const w = Math.max(1, Math.round(wide * ratio)), h = Math.max(1, Math.round(high * ratio));
  const sw = Math.min(width, wide), sh = Math.min(height, high);
  const dw = sw * w / wide, dh = sh * h / high;
  return { w, h, args: [(width - sw) / 2, (height - sh) / 2, sw, sh,
    (w - dw) / 2, (h - dh) / 2, dw, dh] };
}

const scanControls = [
  { key: "exposure", mode: "exposureMode", values: ["exposureTime", "iso"] },
  { key: "whiteBalance", mode: "whiteBalanceMode", values: ["colorTemperature"] },
];
const controlSettings = (settings, control) => Object.fromEntries(
  [control.mode, ...control.values].map((key) => [key, settings[key]]),
);
const sameControl = (settings, expected) => Object.entries(expected).every(
  ([key, value]) => Object.is(settings[key], value),
);
const reportedValue = (value, range) => Number.isFinite(value) && value > 0 &&
  Number.isFinite(range?.min) && Number.isFinite(range?.max) &&
  range.min <= range.max && value >= range.min && value <= range.max;

function snapControl(value, { min, max, step }) {
  const bounded = Math.max(min, Math.min(max, value));
  if (!(step > 0)) return bounded;
  const steps = Math.min(Math.floor((max - min) / step), Math.round((bounded - min) / step));
  return min + step * steps;
}

// Image Capture reports exposure time in units of 100 microseconds.
export function fasterScanExposure(settings, capabilities) {
  const original = settings.exposureTime;
  const times = capabilities.exposureTime, sensitivities = capabilities.iso;
  if (!(original > 110) || !reportedValue(original, times) || !reportedValue(settings.iso, sensitivities))
    return null;
  const product = original * settings.iso;
  const goal = snapControl(100, times);
  const iso = snapControl(product / (goal || 100), sensitivities);
  const exposureTime = snapControl(product / iso, times);
  if (!(exposureTime > 0 && exposureTime < original && iso > 0) ||
      Math.abs(Math.log2(exposureTime * iso / product)) > .1) return null;
  return { exposureTime, iso };
}

// applyConstraints replaces the track's constraints. Retain unrelated video and
// camera controls, replacing only this channel in basic and advanced sets.
function scanConstraints(track, patch, settings, capabilities) {
  // A device can expose a manual setting without retaining an equivalent
  // constraint. Preserve its actual manual values when changing the other side.
  const changes = {};
  for (const control of scanControls) {
    if (!Object.hasOwn(patch, control.mode) && settings[control.mode] === "manual" &&
        capabilities[control.mode]?.includes("manual") &&
        control.values.every((key) => reportedValue(settings[key], capabilities[key])))
      Object.assign(changes, controlSettings(settings, control));
  }
  Object.assign(changes, patch);
  const current = track.getConstraints?.() || {};
  const next = { ...current };
  const advanced = (current.advanced || []).map((entry) => ({ ...entry }));
  for (const key of Object.keys(changes)) {
    delete next[key];
    for (const entry of advanced) delete entry[key];
  }
  next.advanced = [...advanced.filter((entry) => Object.keys(entry).length), changes];
  return next;
}

/** Browser capture owns one stream. Sampling and metadata polling never restart it. */
export class Camera extends EventTarget {
  constructor(video) {
    super();
    this.video = video;
    this.canvas = document.createElement("canvas");
    this.context = this.canvas.getContext("2d", {
      willReadFrequently: true,
      colorSpace: "srgb",
    });
    this.generation = 0;
    this.settings = {};
    this.capabilities = {};
    this.paused = false;
    // Pose-driven capture can keep preview events without CPU pixel readbacks.
    // Explicit captureAnalysis() remains available for visual tracking on demand.
    this.analysisEnabled = true;
    this.exposureRevision = 0;
    this.motion = new FrameMotion();
  }

  emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  async start(deviceId = "") {
    this.stop();
    const generation = ++this.generation;
    if (!navigator.mediaDevices?.getUserMedia)
      throw new Error(
        t("此浏览器无法使用相机。请在 HTTPS 页面使用支持相机的浏览器。"),
      );
    const video = {
      width: { ideal: 1280 },
      height: { ideal: 720 },
      frameRate: { ideal: 30 },
    };
    if (deviceId) video.deviceId = { exact: deviceId };
    else video.facingMode = { ideal: "environment" };
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video,
    });
    if (generation !== this.generation) {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }
    this.stream = stream;
    this.track = stream.getVideoTracks()[0];
    this.track.addEventListener("ended", () => {
      if (this.stream === stream) {
        this.stop();
        this.emit("ended");
      }
    });
    this.track.addEventListener("mute", () => this.emit("muted", true));
    this.track.addEventListener("unmute", () => this.emit("muted", false));
    this.video.srcObject = stream;
    await this.video.play();
    if (generation !== this.generation) return;
    this.readSettings();
    this.capabilities = this.track.getCapabilities?.() || {};
    this.emit("started", {
      settings: this.settings,
      capabilities: this.capabilities,
    });
    this.emit("devices", await this.devices());
    this.poll = setInterval(() => {
      if (!this.paused) this.readSettings();
    }, 500);
    this.schedule();
  }

  async devices() {
    return (await navigator.mediaDevices.enumerateDevices()).filter(
      (d) => d.kind === "videoinput",
    );
  }

  readSettings() {
    if (!this.track) return;
    this.settings = this.track.getSettings();
    this.panoramaLock?.observe(this.settings);
    this.emit("settings", this.settings);
    return this.settings;
  }

  schedule() {
    if (!this.stream || this.paused) return;
    if (this.video.requestVideoFrameCallback)
      this.callback = this.video.requestVideoFrameCallback((time) =>
        this.frame(time),
      );
    else this.fallback = setTimeout(() => {
      const stamp = this.video.getVideoPlaybackQuality?.().totalVideoFrames || this.video.currentTime;
      if (stamp === this.lastPresentation) this.schedule();
      else {
        this.lastPresentation = stamp;
        this.frame(performance.now());
      }
    }, 16);
  }

  frame(time) {
    if (!this.stream || this.paused) return;
    try {
      if (this.video.readyState < 2) return;
      this.inFrame = true;
      this.previewKey = null;
      this.previewPixels = null;
      if (this.analysisEnabled && (this.lastFrame == null || time - this.lastFrame >= 140)) {
        const image = this.captureAnalysis();
        if (image) {
          this.lastFrame = time;
          this.emit("frame", {
            image, still: this.motion.observe(image, time),
            settings: { ...this.settings }, time,
          });
        }
      }
      this.emit("previewframe", { time });
    } finally {
      this.inFrame = false;
      this.previewKey = null;
      this.previewPixels = null;
      this.schedule();
    }
  }

  captureAnalysis(maxEdge = 640) {
    const { videoWidth: width, videoHeight: height } = this.video;
    if (!this.stream || this.video.readyState < 2 || !width || !height ||
        !Number.isFinite(maxEdge) || maxEdge <= 0) return null;
    const scale = Math.min(1, maxEdge / Math.max(width, height));
    const w = Math.max(1, Math.round(width * scale));
    const h = Math.max(1, Math.round(height * scale));
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    this.context.drawImage(this.video, 0, 0, w, h);
    return this.context.getImageData(0, 0, w, h);
  }

  // Reused canvas: consume synchronously. Request capturePreview for an owned
  // pixel snapshot, e.g. when freezing the view or using a CPU renderer.
  previewCanvas(source = null, target = null, scale = 1) {
    const { videoWidth: width, videoHeight: height } = this.video;
    if (!this.stream || this.video.readyState < 2 || !width || !height) return null;
    const rect = captureGeometry(width, height, source, target, scale);
    if (!rect) return null;
    const key = [rect.w, rect.h, ...rect.args].join(",");
    if (this.inFrame && this.previewKey === key) return this.previewSurface;
    if (!this.previewSurface) {
      this.previewSurface = document.createElement("canvas");
      this.previewContext = this.previewSurface.getContext("2d", { colorSpace: "srgb" });
    }
    const canvas = this.previewSurface;
    if (canvas.width !== rect.w || canvas.height !== rect.h) {
      canvas.width = rect.w;
      canvas.height = rect.h;
    } else if (rect.args[4] > 0 || rect.args[5] > 0) {
      this.previewContext.clearRect(0, 0, rect.w, rect.h);
    }
    this.previewContext.drawImage(this.video, ...rect.args);
    this.previewKey = key;
    this.previewPixels = null;
    return canvas;
  }

  capturePreview(source = null, target = null, scale = 1) {
    const canvas = this.previewCanvas(source, target, scale);
    if (!canvas) return null;
    if (this.inFrame && this.previewPixels) return this.previewPixels;
    const image = this.previewContext.getImageData(0, 0, canvas.width, canvas.height);
    if (this.inFrame) this.previewPixels = image;
    return image;
  }

  pause(paused) {
    this.paused = paused;
    if (this.callback !== undefined)
      this.video.cancelVideoFrameCallback?.(this.callback);
    clearTimeout(this.fallback);
    if (!paused && this.stream) {
      this.readSettings();
      this.video
        .play()
        .then(() => this.schedule())
        .catch(() => this.emit("ended"));
    }
  }

  async fixedExposure(seconds, iso) {
    if (!this.track) throw new Error(t("请先开启相机"));
    if (!this.capabilities.exposureMode?.includes("manual"))
      throw new Error(
        t("浏览器未开放手动曝光。请在相机已固定曝光时填写并确认采集参数。"),
      );
    // A later user command owns this channel, even if it selects the same values.
    this.exposureRevision++;
    await this.track.applyConstraints({
      advanced: [
        { exposureMode: "manual", exposureTime: seconds * 10000, iso },
      ],
    });
    this.readSettings();
    const actual = this.settings;
    if (
      actual.exposureMode !== "manual" ||
      !(actual.exposureTime > 0) ||
      !(actual.iso > 0)
    )
      throw new Error(t("浏览器未确认固定曝光参数，仍按未知曝光处理。"));
    return { seconds: actual.exposureTime / 10000, iso: actual.iso };
  }

  /** Best-effort AE/AWB hold, using reported values in Image Capture API units.
   * Unsupported / incomplete channels remain automatic. Never invent metadata.
   * release() is idempotent and returns independent per-channel restore states. */
  async lockForPanorama({ preferFaster = false } = {}) {
    const previous = this.panoramaLock;
    if (previous?.sameSession() && !previous.released) {
      if (previous.releasePromise) {
        await previous.releasePromise;
        return this.lockForPanorama({ preferFaster });
      }
      return previous.promise;
    }
    const track = this.track, stream = this.stream, generation = this.generation;
    const result = {
      exposureLocked: false, whiteBalanceLocked: false,
      exposureStatus: "unavailable", whiteBalanceStatus: "unavailable",
      errors: {},
    };
    const channels = scanControls.map((control) => ({ ...control }));
    const lease = {
      released: false,
      sameSession: () => !!track && !!stream && this.track === track &&
        this.stream === stream && this.generation === generation && track.readyState !== "ended",
      observe: (settings) => {
        if (!active() || lease.releasePromise) return;
        for (const control of channels) {
          if (control.expected && (!sameControl(settings, control.expected) ||
              (control.key === "exposure" && control.revision !== this.exposureRevision))) {
            control.changed = true;
            result[control.key + "Locked"] = false;
            result[control.key + "Status"] = "changed";
          }
        }
      },
    };
    const active = () => lease.sameSession() && this.panoramaLock === lease && !lease.released;
    const read = () => {
      if (!active()) return null;
      this.readSettings();
      return active() ? { ...this.settings } : null;
    };
    const errorText = (error) => String(error?.message || error?.name || error);
    let capabilities;
    this.panoramaLock = lease;
    lease.promise = (async () => {
      try {
        capabilities = track?.getCapabilities?.() || {};
      } catch (error) {
        result.errors.capabilities = errorText(error);
      }
      for (const control of channels) {
        const status = control.key + "Status", flag = control.key + "Locked";
        if (!active() || !capabilities || typeof track.applyConstraints !== "function") continue;
        const modes = capabilities[control.mode];
        if (!Array.isArray(modes) || !modes.includes("manual") ||
            control.values.some((key) => !Number.isFinite(capabilities[key]?.min) ||
              !Number.isFinite(capabilities[key]?.max))) {
          result[status] = "unsupported";
          continue;
        }
        try {
          const before = read();
          if (!before) { result[status] = "superseded"; continue; }
          if (!modes.includes(before[control.mode]) ||
              !control.values.every((key) => reportedValue(before[key], capabilities[key]))) {
            result[status] = "incomplete-settings";
            continue;
          }
          control.original = controlSettings(before, control);
          control.revision = this.exposureRevision;
          if (before[control.mode] === "manual") {
            control.expected = control.original;
            result[flag] = true;
            result[status] = "already-manual";
            continue;
          }
          const patch = { ...control.original, [control.mode]: "manual" };
          const faster = preferFaster && control.key === "exposure"
            ? fasterScanExposure(before, capabilities) : null;
          let rejected = false;
          let actual;
          try {
            await track.applyConstraints(scanConstraints(track, { ...patch, ...faster }, before, capabilities));
          } catch (error) {
            rejected = true;
            result.errors[control.key] = errorText(error);
          }
          actual = read();
          if (faster && actual && actual[control.mode] !== "manual" &&
              control.revision === this.exposureRevision) {
            try {
              await track.applyConstraints(scanConstraints(track, patch, actual, capabilities));
              rejected = false;
              delete result.errors[control.key];
            } catch (error) {
              rejected = true;
              result.errors[control.key] = errorText(error);
            }
            actual = read();
          }
          if (!actual) { result[status] = "superseded"; continue; }
          if (control.key === "exposure" && control.revision !== this.exposureRevision) {
            control.changed = true;
            result[status] = "changed";
            continue;
          }
          // Retain enough ownership to undo a partially reported manual change,
          // but never claim a lock when the required actual values are absent.
          if (actual[control.mode] === "manual") {
            control.expected = controlSettings(actual, control);
            control.owned = true;
          }
          const confirmed = actual[control.mode] === "manual" &&
            control.values.every((key) => reportedValue(actual[key], capabilities[key]));
          result[flag] = !rejected && confirmed;
          result[status] = rejected ? "rejected" : confirmed ? "locked" : "unconfirmed";
        } catch (error) {
          result[status] = "failed";
          result.errors[control.key] = errorText(error);
        }
      }
      try { read(); } catch (error) { result.errors.settings = errorText(error); }
      if (!active()) for (const control of channels) {
        result[control.key + "Locked"] = false;
        result[control.key + "Status"] = "superseded";
      }
      return result;
    })();
    result.release = () => {
      if (lease.releasePromise) return lease.releasePromise;
      lease.releasePromise = (async () => {
        const restored = {};
        for (const control of channels) {
          if (!control.owned) { restored[control.key] = "unchanged"; continue; }
          if (!active()) { restored[control.key] = "skipped-session"; continue; }
          try {
            const current = read();
            if (!current) { restored[control.key] = "skipped-session"; continue; }
            if (control.changed || !sameControl(current, control.expected) ||
                (control.key === "exposure" && control.revision !== this.exposureRevision)) {
              restored[control.key] = "skipped-changed";
              continue;
            }
            await track.applyConstraints(scanConstraints(track, control.original, current, capabilities));
            const actual = read();
            if (!actual) { restored[control.key] = "skipped-session"; continue; }
            // Automatic exposure / WB may already adapt after restoration. Its
            // mode, rather than a stale auto value, confirms that release worked.
            restored[control.key] = actual[control.mode] === control.original[control.mode]
              ? "restored" : "unconfirmed";
          } catch (error) {
            restored[control.key] = "failed";
            result.errors[control.key + "Release"] = errorText(error);
          }
        }
        lease.released = true;
        if (this.panoramaLock === lease) this.panoramaLock = null;
        return restored;
      })();
      return lease.releasePromise;
    };
    return lease.promise;
  }

  stop() {
    this.generation++;
    clearInterval(this.poll);
    clearTimeout(this.fallback);
    if (this.callback !== undefined)
      this.video.cancelVideoFrameCallback?.(this.callback);
    this.stream?.getTracks().forEach((track) => track.stop());
    this.video.srcObject = null;
    this.stream = null;
    this.track = null;
    this.lastFrame = null;
    this.lastPresentation = null;
    this.motion.reset();
    this.previewContext = this.previewSurface = this.previewPixels = null;
    this.emit("stopped");
  }
}

export function parseTime(value) {
  const parts = String(value).trim().split("/").map(Number);
  const seconds =
    parts.length === 2
      ? parts[0] / parts[1]
      : parts.length === 1
        ? parts[0]
        : NaN;
  return Number.isFinite(seconds) && seconds > 0 && seconds <= 86400
    ? seconds
    : NaN;
}

// Media Capture exposureTime is measured in 100 µs units, including fractional units.
// https://w3c.github.io/mediacapture-image/#dfn-exposure-time
export function trackExposure(settings) {
  const t = Number(settings.exposureTime) / 10000;
  const iso = Number(settings.iso);
  return Number.isFinite(t) && t > 0 && Number.isFinite(iso) && iso > 0
    ? { t, iso }
    : null;
}

export function captureExposure(settings, profile) {
  const a = Number(profile.aperture);
  if (!(a >= 0.5 && a <= 64)) return null;
  const mode = profile.mode || "auto";
  const reported = trackExposure(settings);
  if (
    reported &&
    (["auto", "reported"].includes(mode) ||
      (mode === "fixed" && settings.exposureMode === "manual"))
  ) {
    return {
      a,
      ...reported,
      source:
        settings.exposureMode === "manual"
          ? "固定参数 · 预览估测"
          : "浏览器 AE 参数估测 · 可能滞后",
    };
  }
  if (
    mode === "manual" &&
    profile.confirmed &&
    profile.seconds > 0 &&
    profile.iso > 0 &&
    (!settings.exposureMode || settings.exposureMode === "manual") &&
    (!(settings.exposureTime > 0) ||
      Math.abs(Math.log2(settings.exposureTime / 10000 / profile.seconds)) <
        0.15) &&
    (!(settings.iso > 0) ||
      Math.abs(Math.log2(settings.iso / profile.iso)) < 0.1)
  ) {
    return {
      a,
      t: profile.seconds,
      iso: profile.iso,
      source: "手填采集参数 · 预览估测",
    };
  }
  return null;
}

// A known-EV reference remains valid only while acquisition exposure is unchanged.
export function referenceSignature(settings, profile) {
  return JSON.stringify([
    settings.exposureMode ?? null,
    settings.exposureTime ?? null,
    settings.iso ?? null,
    profile.aperture ?? null,
    profile.seconds ?? null,
    profile.iso ?? null,
  ]);
}

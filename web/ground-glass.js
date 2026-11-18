import { defaultResponse, timeLabel } from "./core.js";
import { GroundBurst, groundCenter, groundReading, measuredTransmissionLoss } from "./exposure-tools.js";
import { t } from "./i18n.js";

const $ = (id) => document.getElementById(id);
const number = (value) => Number(value.toFixed(2));
const aperture = (id) => {
  const input = $(id);
  return input.checkValidity() && input.valueAsNumber >= .5 ? input.valueAsNumber : null;
};

export class GroundGlass {
  constructor(actions) {
    this.actions = actions;
    this.state = actions.state;
    this.camera = actions.camera;
  }

  get capturing() { return !!this.capture; }
  get pairing() { return !!this.pair; }

  get lensKey() {
    return `${this.actions.cameraId()}:z${this.camera.settings.zoom || 1}`;
  }

  get profile() {
    const profile = this.state.groundCalibrations[this.lensKey];
    return Number.isFinite(profile?.offset) && profile.referenceAperture >= .5 &&
      profile.referenceAperture <= 256 ? profile : null;
  }

  bind() {
    $("ground-calibrate").addEventListener("click", () => this.start(true));
    $("ground-measure").addEventListener("click", () => this.start(false));
    $("ground-abort").addEventListener("click", () => this.end());
    $("ground-capture").addEventListener("cancel", (event) => {
      event.preventDefault();
      this.end();
    });
    $("ground-compare").addEventListener("click", () => this.startPair());
    $("ground-pair-cancel").addEventListener("click", () => this.cancelPair());
    $("ground-cancel").addEventListener("click", () => this.cancelPair());
    $("ground-apply").addEventListener("click", () => this.apply());
  }

  refresh() {
    const select = $("ground-reference"), selected = select.value;
    const points = this.referencePoints();
    select.replaceChildren(...(points.length ? points.map((point) =>
      new Option(t(`点 ${point.id} · ${point.ev.toFixed(1)} EV100`), point.id))
      : [new Option(t("先冻结取景并添加参考点"), "")]));
    if (points.some((point) => String(point.id) === selected)) select.value = selected;
    $("ground-calibrate").disabled = !points.length;
    const profile = this.profile;
    const calibrated = !!this.camera.track && !!profile;
    $("ground-profile").textContent = t(calibrated
      ? `当前镜头已校准 · f/${number(profile.referenceAperture)} · 偏移 ${number(profile.offset)} EV`
      : "当前镜头尚无毛玻璃校准");
    if (calibrated) $("ground-aperture").value = profile.referenceAperture;
    $("ground-measure").disabled = !calibrated;
    const result = this.result?.key === this.lensKey && this.result;
    $("ground-compare").hidden = !result;
    $("ground-result").textContent = result ? t(`毛玻璃 ${result.ev.toFixed(2)} EV100 · ${timeLabel(result.seconds)}${result.correction.note ? " · " + result.correction.note : ""}`) : "";
    $("ground-source").hidden = !result?.correction?.source;
    if (!$("ground-source").hidden) $("ground-source").href = result.correction.source;
  }

  referencePoints() {
    return this.state.frozen && !this.state.pano && this.actions.absolute() &&
      this.state.frozenProfile === this.state.profileKey ? this.state.spots : [];
  }

  start(reference) {
    if (this.capturing || !this.camera.track || this.state.runningCalibration) return;
    const point = this.referencePoints().find((p) => String(p.id) === $("ground-reference").value);
    const f = aperture(reference ? "ground-aperture" : "ground-working");
    if (!f || (reference ? !point : !this.profile)) {
      this.actions.toast("请检查参考点、镜头校准和光圈");
      return;
    }
    const key = this.lensKey, track = this.camera.track, profile = this.profile;
    const iso = this.state.manual.iso;
    this.cancelPair();
    this.actions.cancelScan();
    if (this.state.frozen) this.actions.toggleFreeze();
    if (!Number.isFinite(this.actions.sceneBase().value)) {
      this.actions.toast("绝对测光需要设置手机采集参数");
      return;
    }
    $("ground-capture").showModal();
    const delay = Number($("ground-delay").value) * 1000;
    const start = performance.now() + delay;
    const timer = setTimeout(() => {
      this.end();
      this.actions.toast("毛玻璃采样未得到稳定读数。请检查遮光、亮度和曝光参数后重试。");
    }, delay + 4500);
    this.capture = { key, track, profileKey: this.state.profileKey,
      f, point: reference ? point : null, iso, profile,
      start, timer, burst: new GroundBurst(start) };
  }

  sample({ image, settings, time }) {
    const capture = this.capture;
    if (!capture) return false;
    if (capture.track !== this.camera.track || capture.key !== this.lensKey ||
        capture.profileKey !== this.state.profileKey) {
      this.end();
      return false;
    }
    if (time <= capture.start) return true;
    const base = this.actions.sceneBase(settings).value;
    const response = this.actions.calibration(settings)?.response || defaultResponse;
    const ev = capture.burst.observe(time, groundCenter(image, base, response));
    if (ev === null) return true;
    this.end();
    if (capture.point) {
      this.state.groundCalibrations[capture.key] = {
        offset: capture.point.ev - ev, referenceAperture: capture.f,
      };
      this.result = null;
      this.actions.persist();
      this.actions.toast("已保存当前镜头的毛玻璃校准");
    } else {
      const result = groundReading(ev, capture.profile, capture.f, capture.iso);
      this.result = { key: capture.key, ev: result.equivalentEv, seconds: result.seconds,
        correction: this.actions.filmCorrection(result.seconds) };
    }
    this.refresh();
    this.actions.playSound();
    return true;
  }

  end() {
    if (!this.capture) return;
    clearTimeout(this.capture.timer);
    this.capture = null;
    $("ground-capture").close();
  }

  startPair() {
    if (this.result?.key !== this.lensKey || !this.camera.track) return;
    this.actions.cancelScan();
    if (this.state.frozen) this.actions.toggleFreeze();
    if (!this.actions.absolute()) {
      this.actions.toast("绝对测光需要设置手机采集参数");
      return;
    }
    this.pair = this.result;
    $("more").close();
    $("ground-pair").hidden = false;
    this.actions.processImage();
  }

  compare(ev) {
    if (this.pair?.key !== this.lensKey || !this.actions.absolute()) {
      this.cancelPair();
      this.actions.toast("请用同一镜头和有效的绝对读数比对");
      return;
    }
    const loss = measuredTransmissionLoss(ev, this.pair.ev);
    if (loss === null) {
      this.actions.toast("毛玻璃读数高于场景点。请检查是否测量了同一物点。");
      return;
    }
    this.proposedLoss = loss;
    $("ground-confirm-text").textContent = t(`实测光损 +${number(loss)} EV。启用时将使用此值代替滤镜和皮腔计算值。`);
    $("ground-confirm").showModal();
  }

  apply() {
    if (!this.pair || this.pair.key !== this.lensKey || !Number.isFinite(this.proposedLoss)) return;
    this.state.measuredLoss = this.proposedLoss;
    this.state.lossSource = "measured";
    this.cancelPair();
    this.actions.updateOpticalLoss();
  }

  cancelPair() {
    this.pair = null;
    this.proposedLoss = null;
    $("ground-confirm").close();
    $("ground-pair").hidden = true;
  }

  cancel() {
    this.end();
    this.cancelPair();
  }
}

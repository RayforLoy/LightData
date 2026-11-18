import { t, language } from "./i18n.js";

const $ = (id) => document.getElementById(id);
const DEFAULT_SOUND = new URL("./audio/sony_focus.mp3", import.meta.url).href;
const SOUND_SOURCE = "https://freesound.org/people/iainmccurdy/sounds/613889/";

function openStore() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("lightdata.web.finder-media", 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore("snapshots", { keyPath: "id" });
      request.result.createObjectStore("preferences");
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("Media storage upgrade is blocked"));
  });
}

function png(image) {
  const canvas = document.createElement("canvas");
  canvas.width = image.width;
  canvas.height = image.height;
  canvas.getContext("2d").putImageData(image, 0, 0);
  return new Promise((resolve, reject) => canvas.toBlob((blob) => {
    if (blob) resolve(blob);
    else reject(new Error("Could not encode snapshot"));
  }, "image/png"));
}

export class FinderMedia {
  constructor(actions) {
    this.actions = actions;
    this.state = actions.state;
    this.audio = new Audio(DEFAULT_SOUND);
    this.soundRevision = 0;
    this.listRevision = 0;
  }

  async request(name, mode, callback) {
    this.database ??= openStore();
    const db = await this.database;
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(name, mode);
      const request = callback(transaction.objectStore(name));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onabort = () => reject(transaction.error);
    });
  }

  bind() {
    const opacity = $("overlay-opacity");
    opacity.value = this.state.overlayOpacity * 100;
    $("overlay-opacity-value").textContent = `${opacity.value}%`;
    opacity.addEventListener("input", () => {
      this.state.overlayOpacity = opacity.valueAsNumber / 100;
      $("overlay-opacity-value").textContent = `${opacity.value}%`;
      this.actions.persist();
      this.layoutOverlay();
    });
    $("snapshot-save").addEventListener("click", () => void this.save());
    $("snapshot-hide").addEventListener("click", () => this.hideOverlay());

    $("meter-sound").checked = this.state.soundEnabled;
    $("meter-sound").addEventListener("change", (event) => {
      this.state.soundEnabled = event.target.checked;
      if (!this.state.soundEnabled) this.audio.pause();
      this.actions.persist();
    });
    $("meter-sound-import").addEventListener("click", () => $("meter-sound-file").click());
    $("meter-sound-file").addEventListener("change", (event) => {
      const file = event.target.files[0];
      event.target.value = "";
      if (file) void this.importSound(file);
    });
    $("meter-sound-restore").addEventListener("click", () => void this.restoreSound());
    this.request("preferences", "readonly", (store) => store.get("sound"))
      .then((file) => {
        if (file instanceof Blob && this.soundRevision === 0) this.useSound(file);
      }).catch(() => { /* The bundled sound remains usable. */ });
  }

  async refresh() {
    $("snapshot-save").disabled = !this.state.image;
    const revision = ++this.listRevision;
    const list = $("snapshot-list");
    try {
      const rows = await this.request("snapshots", "readonly", (store) => store.getAll());
      if (revision !== this.listRevision) return;
      rows.sort((a, b) => b.time - a.time);
      list.replaceChildren(...rows.map((row) => {
        const wrapper = document.createElement("div");
        const select = document.createElement("button");
        const attitude = row.level ? t(`横滚 ${row.level.roll.toFixed(1)}° · 俯仰 ${row.level.pitch.toFixed(1)}°`) : "";
        select.textContent = `${t(row.format)} · ${row.focal} mm · ${new Date(row.time).toLocaleString(language)}${attitude ? " · " + attitude : ""}`;
        select.addEventListener("click", () => {
          this.showOverlay(row);
          $("more").close();
        });
        const remove = document.createElement("button");
        remove.className = "quiet";
        remove.textContent = "×";
        remove.setAttribute("aria-label", t("删除构图快照"));
        remove.addEventListener("click", () => void this.remove(row.id));
        wrapper.append(select, remove);
        return wrapper;
      }));
    } catch {
      if (revision === this.listRevision) list.textContent = t("浏览器未开放本地媒体存储");
    }
  }

  async save() {
    const frame = this.actions.snapshotFrame();
    if (!frame?.image) return;
    $("snapshot-save").disabled = true;
    try {
      const row = { id: crypto.randomUUID(), time: Date.now(), focal: frame.focal,
        format: frame.format, field: frame.field, level: frame.level, blob: await png(frame.image) };
      await this.request("snapshots", "readwrite", (store) => store.put(row));
      this.actions.toast("已保存构图快照");
    } catch {
      this.actions.toast("保存构图失败，请检查浏览器存储");
    } finally {
      await this.refresh();
    }
  }

  async remove(id) {
    try {
      await this.request("snapshots", "readwrite", (store) => store.delete(id));
      if (this.overlay?.id === id) this.hideOverlay();
      await this.refresh();
    } catch {
      this.actions.toast("无法删除构图快照");
    }
  }

  showOverlay(row) {
    this.hideOverlay();
    this.overlay = row;
    this.overlayUrl = URL.createObjectURL(row.blob);
    const layer = $("framing-overlay");
    layer.firstElementChild.src = this.overlayUrl;
    layer.hidden = false;
    $("snapshot-hide").hidden = false;
    this.layoutOverlay();
  }

  hideOverlay() {
    if (!this.overlay) return;
    URL.revokeObjectURL(this.overlayUrl);
    this.overlay = null;
    const layer = $("framing-overlay");
    layer.hidden = true;
    layer.firstElementChild.removeAttribute("src");
    $("snapshot-hide").hidden = true;
  }

  layoutOverlay() {
    if (!this.overlay) return;
    const layer = $("framing-overlay"), image = layer.firstElementChild;
    const old = this.overlay.field, current = this.actions.framingField();
    layer.style.opacity = this.state.overlayOpacity;
    image.style.width = `${old && current ? 100 * old.x / current.x : 100}%`;
    image.style.height = `${old && current ? 100 * old.y / current.y : 100}%`;
    image.style.objectFit = old && current ? "fill" : "contain";
  }

  useSound(file) {
    this.audio.pause();
    if (this.audioUrl) URL.revokeObjectURL(this.audioUrl);
    this.audioUrl = file ? URL.createObjectURL(file) : null;
    this.audio.src = this.audioUrl || DEFAULT_SOUND;
  }

  beep() {
    if (!this.state.soundEnabled) return;
    this.audio.currentTime = 0;
    void this.audio.play().catch(() => { /* The browser may require a user gesture. */ });
  }

  async importSound(file) {
    const revision = ++this.soundRevision;
    if (!file.size || file.size > 10 * 1024 * 1024 || (file.type && !file.type.startsWith("audio/"))) {
      this.actions.toast("请选择不超过 10 MB 的音频文件");
      return;
    }
    const url = URL.createObjectURL(file);
    const probe = new Audio();
    try {
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("Audio metadata timed out")), 5000);
        probe.onloadedmetadata = () => { clearTimeout(timeout); resolve(); };
        probe.onerror = () => { clearTimeout(timeout); reject(probe.error); };
        probe.src = url;
      });
      if (revision !== this.soundRevision) return;
      await this.request("preferences", "readwrite", (store) => store.put(file, "sound"));
      if (revision !== this.soundRevision) return;
      this.useSound(file);
      this.beep();
      this.actions.toast("已保存测光确认音");
    } catch {
      this.actions.toast("无法读取或保存此音频");
    } finally {
      URL.revokeObjectURL(url);
      probe.removeAttribute("src");
      probe.load();
    }
  }

  async restoreSound() {
    const revision = ++this.soundRevision;
    try {
      await this.request("preferences", "readwrite", (store) => store.delete("sound"));
      if (revision !== this.soundRevision) return;
    } catch {
      this.actions.toast("浏览器未开放本地媒体存储");
    }
    this.useSound(null);
    this.beep();
    this.actions.notice("已恢复默认测光确认音", "Sony A7III Focus Beep · iainmccurdy · Freesound · CC BY 4.0", SOUND_SOURCE);
  }
}

import { orientationMatrix } from "./panorama.js";

const degrees = 180 / Math.PI;
const tolerance = Math.sin(1.5 / degrees);

export function finderLevel(beta, gamma, screenAngle) {
  const axes = orientationMatrix(0, beta, gamma, screenAngle);
  if (!axes) return null;
  const [, , , , , , upX, upY, upZ] = axes;
  const distance = Math.hypot(upX, upY);
  const pitch = Math.atan2(-upZ, distance) * degrees;
  const roll = Math.atan2(upX, upY);
  const overhead = Math.abs(pitch) >= 65;
  const travel = .77 / Math.max(Math.sin(9 / degrees), distance);
  return {
    pitch, roll: roll * degrees, overhead,
    x: upX * travel, y: -upY * travel,
    level: overhead ? distance <= tolerance : Math.abs(Math.sin(roll)) <= tolerance,
  };
}

export function paintFinderLevel(canvas, reading) {
  canvas.hidden = !reading;
  if (!reading) return;
  const ctx = canvas.getContext("2d");
  const cx = canvas.width / 2, cy = canvas.height / 2;
  const accent = reading.level ? "#6dd8a0" : "#ffbd59";
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.translate(cx, cy);
  const stroke = (color, trace) => {
    ctx.beginPath();
    trace();
    ctx.strokeStyle = "#101112";
    ctx.lineWidth = 7;
    ctx.stroke();
    ctx.strokeStyle = color;
    ctx.lineWidth = 3;
    ctx.stroke();
  };
  const segment = (color, x, y, u, v) => stroke(color, () => {
    ctx.moveTo(x, y);
    ctx.lineTo(u, v);
  });
  if (reading.overhead) {
    stroke("#f0efea", () => ctx.arc(0, 0, 29, 0, Math.PI * 2));
    stroke("#f0efea", () => ctx.arc(0, 0, 6, 0, Math.PI * 2));
    ctx.beginPath();
    ctx.arc(reading.x * 29, reading.y * 29, 5.5, 0, Math.PI * 2);
    ctx.fillStyle = accent;
    ctx.fill();
  } else {
    const angle = reading.roll / degrees;
    const x = Math.cos(angle) * 41, y = Math.sin(angle) * 41;
    segment("#f0efea", -61, 0, -49, 0);
    segment("#f0efea", 49, 0, 61, 0);
    segment(accent, -x, -y, x, y);
  }
  ctx.resetTransform();
}

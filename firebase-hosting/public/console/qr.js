/* A QR code as inline SVG, for TOTP enrolment.
 *
 * Drawn here, in the page, from the otpauth:// URI the Firebase SDK builds.
 * A QR *service* would receive that URI — and the URI is the secret — so the
 * picture is never fetched from anywhere; the CSP's img-src does not name a
 * QR origin and must not start to.
 */
import qrcode from "./vendor/qrcode.js";

// otpauth:// URIs are ASCII, but a display name with a non-Latin character
// would otherwise be truncated to its low byte by the library's default.
qrcode.stringToBytes = qrcode.stringToBytesFuncs["UTF-8"];

/** SVG markup for `text`, sized by CSS (the viewBox scales). */
export function qrSvg(text) {
  const qr = qrcode(0, "M"); // version chosen automatically; M survives a dim phone screen
  qr.addData(text, "Byte");
  qr.make();
  return qr.createSvgTag({ cellSize: 4, margin: 8, scalable: true });
}

'use strict';

function canonicalFilename(value) {
    const name = String(value);
    if (!/\.(png|jpe?g|webp)$/i.test(name)) return null;
    if (name.indexOf('Screenshot_') === 0) return name;
    if (name.indexOf('.pending-') === 0) {
        const start = name.indexOf('Screenshot_');
        if (start >= 0) return name.substring(start);
    }
    return null;
}

function completeImage(bytes) {
    const n = bytes.length;
    if (n < 32) return false;
    function byte(i) { return bytes[i] & 255; }
    if (byte(0) === 255 && byte(1) === 216) return byte(n - 2) === 255 && byte(n - 1) === 217;
    if (byte(0) === 137 && byte(1) === 80 && byte(2) === 78 && byte(3) === 71) {
        return byte(n - 8) === 73 && byte(n - 7) === 69 && byte(n - 6) === 78 && byte(n - 5) === 68;
    }
    if (byte(0) === 82 && byte(1) === 73 && byte(2) === 70 && byte(3) === 70) {
        const size = byte(4) + byte(5) * 256 + byte(6) * 65536 + byte(7) * 16777216;
        return size + 8 <= n && byte(8) === 87 && byte(9) === 69 && byte(10) === 66 && byte(11) === 80;
    }
    return false;
}

module.exports = { canonicalFilename: canonicalFilename, completeImage: completeImage };

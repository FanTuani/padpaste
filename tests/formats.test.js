const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { canonicalFilename, completeImage } = require('../android/formats');

const filename = 'Screenshot_2026-01-01-12-00-00-123_com.example.app.jpg';
test('a pending screenshot and its final name share one identity', () => {
    assert.equal(canonicalFilename('.pending-123-' + filename), canonicalFilename(filename));
});
test('unrelated images and temporary non-images are ignored', () => {
    for (const name of ['IMG_123.jpg', 'photo.png', filename + '.tmp', 'Screenshot_foo.txt']) {
        assert.equal(canonicalFilename(name), null);
    }
});
test('a complete PNG is accepted; a truncated write is not', () => {
    const image = fs.readFileSync(path.join(__dirname, 'fixtures/image.png'));
    assert.equal(completeImage(image), true);
    assert.equal(completeImage(image.subarray(0, image.length - 5)), false);
});
test('Java signed bytes are treated the same as unsigned PNG bytes', () => {
    const image = fs.readFileSync(path.join(__dirname, 'fixtures/image.png'));
    assert.equal(completeImage(Int8Array.from(image)), true);
});

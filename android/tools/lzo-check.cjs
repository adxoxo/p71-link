// Independent check: decode Java LiteralLzo output with the lzo1x package decoder.
// Usage: node lzo-check.cjs <path-to-lzo1x/dist/index.cjs> <vectors.txt>
'use strict';
const fs = require('fs');
const { lzo1xDecompress } = require(process.argv[2]);
const lines = fs.readFileSync(process.argv[3], 'ascii').trim().split('\n');
let ok = 0, bad = 0;
for (const line of lines) {
  const [inHex, encHex] = line.split(' ');
  const input = Buffer.from(inHex, 'hex');
  try {
    const out = Buffer.from(lzo1xDecompress(new Uint8Array(Buffer.from(encHex, 'hex')), input.length));
    if (out.equals(input)) ok++; else bad++;
  } catch (e) { bad++; }
}
console.log(`lzo1x independent decode: ${ok} ok, ${bad} failed`);
process.exit(bad === 0 && ok > 0 ? 0 : 1);

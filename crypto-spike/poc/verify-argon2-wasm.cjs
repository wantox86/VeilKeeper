delete global.fetch;
const argon2 = require('argon2-browser');

(async () => {
  const salt = new Uint8Array([0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15]);
  const result = await argon2.hash({
    pass: "correct horse battery staple",
    salt: salt,
    time: 3,
    mem: 64*1024,
    parallelism: 4,
    hashLen: 32,
    type: argon2.ArgonType.Argon2id,
  });
  console.log("argon2-browser (WASM, ref C impl):", result.hashHex);

  // also official RFC 9106 vector
  const rfcSalt = new Uint8Array(16).fill(2);
  const rfcPass = new Uint8Array(32).fill(1);
  const rfcSecret = new Uint8Array(8).fill(3);
  const rfcAd = new Uint8Array(12).fill(4);
  const rfcResult = await argon2.hash({
    pass: rfcPass,
    salt: rfcSalt,
    secret: rfcSecret,
    ad: rfcAd,
    time: 3,
    mem: 32,
    parallelism: 4,
    hashLen: 32,
    type: argon2.ArgonType.Argon2id,
  });
  console.log("RFC9106 vector result:", rfcResult.hashHex);
  console.log("RFC9106 expected     :", "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659");
})().catch(e => { console.error("ERR", e.message || e); process.exit(1); });

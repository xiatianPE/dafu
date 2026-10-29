// 在页面脚本之前运行：把 dafu-* 前缀的 localStorage 读写同步到原生 Store（window.Native）。
// 没有 window.Native（例如直接在浏览器里打开 dafu.html）时，完全退回普通 localStorage。
(function () {
  try {
    if (!window.Storage) return;
    var P = Storage.prototype, g = P.getItem, s = P.setItem, r = P.removeItem;
    function mine(o, k) {
      return o === window.localStorage && typeof k === 'string' && k.indexOf('dafu-') === 0 && window.Native;
    }
    P.getItem = function (k) {
      if (mine(this, k)) {
        try {
          var v = window.Native.get(k);
          if (v !== null && v !== undefined) return v;
        } catch (e) {}
      }
      return g.call(this, k);
    };
    P.setItem = function (k, v) {
      if (mine(this, k)) { try { window.Native.set(k, String(v)); } catch (e) {} }
      return s.call(this, k, v);
    };
    P.removeItem = function (k) {
      if (mine(this, k)) { try { window.Native.remove(k); } catch (e) {} }
      return r.call(this, k);
    };
  } catch (e) {}
})();

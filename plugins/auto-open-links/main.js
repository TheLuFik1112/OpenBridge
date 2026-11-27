// Opens a received link in the browser.
// Accepts full links (https://ya.ru/path?q=1) and bare domains (google.com).

var TLDS = 'com|ru|org|net|info|io|me|dev|app|ai|co|tv|xyz|site|online|store|pro|biz|edu|gov|mil|int|link|live|news|tech|cloud|space|top|club|shop|su|by|kz|ua|de|uk|fr|it|es|nl|pl|tr|cn|jp|in|br|au|ca|ch|se|no|fi|dk|cz|at|be|pt|gr|il|kr|mx|ar|cl|za|ae|sa|рф';

function obIsHost(value) {
  if (!/^[A-Za-z0-9.-]+(:\d{1,5})?$/.test(value)) return false;
  return value.indexOf('.') > 0;
}

function onTextReceived(text, senderName) {
  var value = String(text || '').trim();
  if (!value || /\s/.test(value)) return;

  var full = /^https?:\/\/[^\/?#]+/i.exec(value);
  if (full) {
    var host = full[0].replace(/^https?:\/\//i, '');
    if (obIsHost(host)) ob.openUrl(value);
    return;
  }

  var bare = value.split(/[\/?#]/)[0].replace(/:\d{1,5}$/, '');
  if (!obIsHost(bare)) return;

  var parts = bare.split('.');
  var tld = parts[parts.length - 1].toLowerCase();
  if (!new RegExp('^(' + TLDS + ')$').test(tld)) return;

  ob.openUrl('https://' + value);
}

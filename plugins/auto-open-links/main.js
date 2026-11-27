function onTextReceived(text, senderName) {
  var url = String(text || '').trim();
  if (/^https?:\/\/\S+$/.test(url)) {
    ob.openUrl(url);
  }
}

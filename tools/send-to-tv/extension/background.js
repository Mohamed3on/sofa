// Toolbar button: send the YouTube video in this tab, from where it is, to SmartTube on the TV.
chrome.action.onClicked.addListener(async (tab) => {
  const url = new URL(tab.url);
  if (!/(^|\.)youtube\.com$|^youtu\.be$/.test(url.hostname)) return badge(tab.id, '!', 'Not a YouTube page');
  try {
    // Pause it here and take its position, so the TV carries on from the same second.
    const [{ result: t }] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => { const v = document.querySelector('video'); if (!v) return 0; v.pause(); return Math.floor(v.currentTime); },
    });
    if (t) url.searchParams.set('t', `${t}s`);
    const res = await chrome.runtime.sendNativeMessage('com.mohamed3on.send_to_tv', { url: url.href });
    res.ok ? badge(tab.id, '✓', 'Playing on the TV') : badge(tab.id, '!', res.error);
  } catch (e) {
    badge(tab.id, '!', String(e.message ?? e));
  }
});

function badge(tabId, text, title) {
  chrome.action.setBadgeText({ tabId, text });
  chrome.action.setTitle({ tabId, title });
  setTimeout(() => chrome.action.setBadgeText({ tabId, text: '' }), 4000);
}

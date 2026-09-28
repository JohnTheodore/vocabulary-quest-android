(function() {
  if (window.__ebvSpellingKeyboardInstalled) return;
  window.__ebvSpellingKeyboardInstalled = true;

  const states = new WeakMap();
  const pageId = String(Date.now()) + '-' + String(Math.random());
  let nextRequestId = 0;
  let pending = null;

  function activeInput() {
    if (document.visibilityState === 'hidden') return null;
    const input = document.querySelector('.exercise-phase:not(.exercise-cooldown) .card .spell-hidden-input[aria-label="Type spelling"]');
    if (!input || !input.isConnected || !(input.offsetWidth || input.offsetHeight)) return null;
    return input;
  }

  function checkAndShowKeyboard() {
    const input = activeInput();
    if (!input) return;
    let state = states.get(input);
    if (!state) {
      state = { attempts: 0, waitingForResume: false, done: false };
      states.set(input, state);
    }
    if (state.done || state.waitingForResume || state.attempts >= 2 || pending) return;

    // Focus the DOM input for physical keys as well as the IME. Mark pending
    // before focusin fires, but only mark done after native reports visibility.
    const id = pageId + '-' + String(++nextRequestId);
    pending = { id, input, state };
    try { input.focus({ preventScroll: true }); } catch (e) { input.focus(); }
    if (!window.AndroidKeyboard || !window.AndroidKeyboard.showSpellingKeyboard) {
      pending = null;
      return;
    }
    state.attempts++;
    window.AndroidKeyboard.showSpellingKeyboard(id);
  }

  window.__ebvSpellingKeyboardResult = function(id, result) {
    if (!pending || pending.id !== id) return;
    const { input, state } = pending;
    pending = null;
    if (result === 'shown') {
      state.done = true;
    } else if (result === 'deferred') {
      state.attempts--;
      state.waitingForResume = true;
    } else if (result === 'not-shown' && activeInput() === input) {
      // At most one follow-up request for a keyboard that failed to appear.
      setTimeout(checkAndShowKeyboard, 350);
    }
    if (activeInput() !== input) checkAndShowKeyboard();
  };

  const observer = new MutationObserver(checkAndShowKeyboard);
  observer.observe(document, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['class']
  });
  window.addEventListener('focusin', checkAndShowKeyboard);
  window.addEventListener('pageshow', checkAndShowKeyboard);
  window.addEventListener('focus', function() {
    const input = activeInput();
    if (input && states.get(input)?.waitingForResume) {
      states.get(input).waitingForResume = false;
    }
    checkAndShowKeyboard();
  });
  window.addEventListener('ebv-native-lifecycle', function(e) {
    if (e.detail && (e.detail.type === 'activity-on-resume' || e.detail.type === 'webview-on-resume')) {
      const input = activeInput();
      if (input && states.get(input)?.waitingForResume) {
        states.get(input).waitingForResume = false;
      }
      checkAndShowKeyboard();
    }
  });
  checkAndShowKeyboard();
})();

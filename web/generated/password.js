const $ = id => document.getElementById(id);
const hex = bytes => Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('');
$('hash-form').addEventListener('submit', async event => {
  event.preventDefault();
  const password = $('new-password').value, confirm = $('confirm-password').value;
  if (password.length < 14 || password.length > 256 || password !== confirm) { $('hash-message').textContent = 'Use at least 14 characters and make sure both passwords match.'; return; }
  const button = event.submitter; button.disabled = true; $('hash-message').textContent = 'Creating the hash locally in this browser…';
  try {
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 600000 }, key, 256);
    $('hash-value').value = `pbkdf2-sha256$600000$${hex(salt)}$${hex(bits)}`;
    $('new-password').value = ''; $('confirm-password').value = ''; $('hash-result').hidden = false;
    $('hash-message').textContent = 'Hash ready. No network request was made. Copy it into Render below.';
  } catch { $('hash-message').textContent = 'This browser could not generate the hash. Open this page over HTTPS in Chrome.'; }
  finally { button.disabled = false; }
});
$('copy-hash').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('hash-value').value); $('hash-message').textContent = 'Copied. Paste it into HOUSEHOLD_PASSWORD_HASH in Render.'; }
  catch { $('hash-value').focus(); $('hash-value').select(); $('hash-message').textContent = 'Select and copy the highlighted hash.'; }
});

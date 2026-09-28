/** Explicit deletion only; nothing is transmitted. */
(function () {
'use strict';
const erase = document.querySelector<HTMLButtonElement>('#eraseBtn');
const status = document.querySelector<HTMLParagraphElement>('#eraseStatus');
if (!erase || !status) throw new Error('Missing privacy controls.');
erase.addEventListener('click', function () {
  try {
    sessionStorage.removeItem('rr_diag');
    sessionStorage.removeItem('rr_case');
    status.textContent = 'Сохранённая анкета и номер случая удалены из этой вкладки.';
  } catch {
    status.textContent = 'Браузер не позволил очистить хранилище. Удалите данные этого сайта в настройках браузера.';
  }
});
})();

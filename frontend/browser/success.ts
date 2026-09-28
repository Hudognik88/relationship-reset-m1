/** Review/export of a local completed case. It never confirms an order or payment. */
(function () {
'use strict';
const fieldLabels: Record<RRField, string> = {age:'Возраст',stage:'Продолжительность отношений',situation:'Что произошло',safety:'Безопасность',boundary:'Границы общения',timing:'Когда это произошло',partner:'Что сделал партнёр',user:'Что сделали вы',recurrence:'Как часто это повторяется',helpful:'Что помогало',success:'Что помогало: ваши слова',failureType:'Что ухудшало ситуацию',failure:'Что ухудшало ситуацию: ваши слова',goal:'Что сейчас нужно'};
const choiceLabels: Partial<Record<RRField, Readonly<Record<string, string>>>> = {
age:{adult:'18 лет или старше'},
safety:{no:'Указанных рисков нет'},
boundary:{clear:'Просьбы о паузе или прекращении общения не было',space:'Попросили дать пространство / сделать паузу',no_contact:'Попросили не связываться',unsure:'Не уверен(а)'},
stage:{under6m:'Меньше 6 месяцев','6to12m':'6–12 месяцев','1to3y':'1–3 года','3to7y':'3–7 лет','7plus':'Больше 7 лет'},
timing:{today:'Сегодня',yesterday:'Вчера','2to3days':'2–3 дня назад',older:'Раньше'},
partner:{talk:'Хотел(а) поговорить',quiet:'Замолчал(а)',angry:'Сердился / сердилась',space:'Попросил(а) пространство',distant:'Держался / держалась отстранённо',left:'Ушёл / ушла',other:'Другое'},
user:{talk:'Продолжал(а) пытаться поговорить',apology:'Извинился / извинилась',defend:'Защищал(а) свою позицию',space:'Дал(а) пространство',messages:'Отправлял(а) ещё сообщения',withdraw:'Тоже отстранился / отстранилась',help:'Пытался / пыталась помочь делом',other:'Другое'},
recurrence:{first:'Впервые',sometimes:'Иногда',often:'Часто',always:'Почти всегда'},
helpful:{pause:'Пауза',listen:'Выслушать',practical:'Практическая помощь',none:'Ничего',unsure:'Не знаю'},
failureType:{messages:'Новые сообщения',apology:'Повторные извинения',talk:'Попытки поговорить',pause:'Пауза',other:'Другое',none:'Ничего',unsure:'Не знаю'},
goal:{talk:'Поговорить',apology:'Извиниться',calm:'Успокоиться',space:'Дать пространство',understand:'Лучше понять ситуацию',pattern:'Изменить повторяющуюся ситуацию',other:'Другое'}
};

function element<T extends HTMLElement>(selector: string): T {
  const node = document.querySelector<T>(selector);
  if (!node) throw new Error('Missing saved-case element: ' + selector);
  return node;
}

const intakeArea = element<HTMLDivElement>('#intakeArea');
const intake = element<HTMLTextAreaElement>('#intake');
const caseLine = element<HTMLParagraphElement>('#caseLine');
const copyStatus = element<HTMLParagraphElement>('#copyStatus');
const stateMsg = element<HTMLParagraphElement>('#stateMsg');
const fields = Object.keys(fieldLabels) as RRField[];

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function validIntake(value: unknown): value is RRIntake {
  if (!isRecord(value) || value.age !== 'adult' || value.safety !== 'no') return false;
  if (Object.keys(value).length !== fields.length || !fields.every(function (key) {
    return Object.prototype.hasOwnProperty.call(value, key) && typeof value[key] === 'string';
  })) return false;
  if (!(Object.keys(choiceLabels) as RRChoiceField[]).every(function (key) {
    const choices = choiceLabels[key];
    const answer = value[key];
    return Boolean(choices && typeof answer === 'string' && Object.prototype.hasOwnProperty.call(choices, answer));
  })) return false;
  if (typeof value.situation !== 'string' || !value.situation.trim() || value.situation.length > 2000) return false;
  if (typeof value.success !== 'string' || value.success.length > 1000) return false;
  if (typeof value.failure !== 'string' || value.failure.length > 1000) return false;
  return true;
}

function validSavedCase(value: unknown, caseId: string | null): value is RRStoredCase {
  if (!isRecord(value) || value.schemaVersion !== 'm1-cis-v1' || value.mode !== 'STANDARD' ||
      typeof caseId !== 'string' || !/^cis_[a-f0-9]{24}$/.test(caseId) || value.case_id !== caseId) return false;
  if (typeof value.ts !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value.ts)) return false;
  const time = Date.parse(value.ts);
  if (!Number.isFinite(time) || time > Date.now() || new Date(time).toISOString() !== value.ts) return false;
  if (!validIntake(value.data)) return false;
  if (typeof ResetLogic === 'undefined' || typeof ResetLogic.move !== 'function' || ResetLogic.move(value.data).mode !== 'STANDARD') return false;
  return true;
}

function clearSavedCase(): boolean {
  let cleared = true;
  ['rr_diag', 'rr_case'].forEach(function (key) {
    try { sessionStorage.removeItem(key); } catch { cleared = false; }
  });
  return cleared;
}

function hideIntake(): void {
  intakeArea.hidden = true;
  intake.value = '';
  caseLine.textContent = '';
  copyStatus.textContent = '';
}

function loadIntake(): void {
  hideIntake();
  let raw: string | null;
  let caseId: string | null;
  try {
    raw = sessionStorage.getItem('rr_diag');
    caseId = sessionStorage.getItem('rr_case');
  } catch {
    stateMsg.textContent = 'Хранилище этой вкладки недоступно. Здесь нельзя показать анкету. Вы можете вернуться к бесплатному разбору.';
    return;
  }
  if (!raw && !caseId) {
    stateMsg.textContent = 'В этой вкладке нет завершённой анкеты. Вернитесь к бесплатному разбору, если хотите заполнить её.';
    return;
  }
  let saved: unknown;
  try {
    if (!raw || raw.length > 24000) throw new Error('invalid');
    saved = JSON.parse(raw);
    if (!validSavedCase(saved, caseId)) throw new Error('invalid');
  } catch {
    const removed = clearSavedCase();
    stateMsg.textContent = removed
      ? 'Сохранённая анкета неполная или не подходит для этой версии. Она удалена из этой вкладки. Начните бесплатный разбор заново.'
      : 'Сохранённая анкета не подходит для этой версии. Она не показана. Удалите данные сайта в настройках браузера и начните заново.';
    return;
  }
  const lines = [
    'Relationship Reset — ваша анкета', 'Номер случая: ' + caseId,
    'Заполнено: ' + new Date(saved.ts).toLocaleString('ru-RU'),
    'Это не подтверждение оплаты или заказа.', ''
  ];
  fields.forEach(function (key) {
    const choices = choiceLabels[key];
    const answer = choices ? choices[saved.data[key]] : saved.data[key];
    lines.push(fieldLabels[key] + ': ' + (answer || 'Не указано'));
  });
  intake.value = lines.join('\n');
  caseLine.textContent = 'Номер случая: ' + caseId;
  stateMsg.textContent = 'Завершённая анкета доступна только в этой вкладке. Она никуда не отправлена.';
  intakeArea.hidden = false;
}

element<HTMLButtonElement>('#copyBtn').addEventListener('click', async function () {
  if (!intake.value) {
    copyStatus.textContent = 'Нет анкеты для копирования.';
    return;
  }
  let copied = false;
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(intake.value);
      copied = true;
    }
  } catch { /* Manual copy remains available. */ }
  if (!copied) {
    intake.focus();
    intake.select();
    intake.setSelectionRange(0, intake.value.length);
    try { copied = document.execCommand('copy') === true; } catch { copied = false; }
  }
  copyStatus.textContent = copied
    ? 'Анкета скопирована. Она никуда не отправлена.'
    : 'Автоматически скопировать не удалось. Текст выделен: выберите «Копировать» в меню вашего устройства.';
});
element<HTMLButtonElement>('#eraseBtn').addEventListener('click', function () {
  const removed = clearSavedCase();
  hideIntake();
  stateMsg.textContent = removed
    ? 'Анкета и номер случая удалены из этой вкладки. Скопированный ранее текст нужно удалить отдельно.'
    : 'Анкета скрыта, но браузер не позволил очистить хранилище. Удалите данные этого сайта в настройках браузера.';
});
window.addEventListener('pageshow', loadIntake);
loadIntake();
})();

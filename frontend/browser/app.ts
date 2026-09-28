/** Local questionnaire only: no network requests or payment transitions. */
(function () {
'use strict';
const options: Record<RRChoiceField, readonly RRChoice[]> = {
 age:[['adult','Да, мне 18 лет или больше'],['minor','Нет, мне меньше 18 лет']],
 stage:[['under6m','Меньше 6 месяцев'],['6to12m','6–12 месяцев'],['1to3y','1–3 года'],['3to7y','3–7 лет'],['7plus','Больше 7 лет']],
 safety:[['no','Нет, ничего из перечисленного'],['yes_unsure','Да или не уверен(а)']],
 boundary:[['clear','Прямого запрета или просьбы о паузе нет'],['space','Просит пространства или паузу'],['no_contact','Просит не писать / не звонить или заблокировал(а)'],['unsure','Не знаю / непонятно']],
 timing:[['today','Сегодня'],['yesterday','Вчера'],['2to3days','2–3 дня назад'],['older','Больше 3 дней назад']],
 partner:[['talk','Хотел(а) поговорить'],['quiet','Замолчал(а)'],['angry','Разозлился / разозлилась'],['space','Попросил(а) пространства'],['distant','Вёл / вела себя обычно, но отстранённо'],['left','Ушёл / ушла или перестал(а) отвечать'],['other','Другое']],
 user:[['talk','Продолжал(а) разговор'],['apology','Извинился / извинилась'],['defend','Защищал(а) свою позицию'],['space','Дал(а) пространство'],['messages','Отправлял(а) ещё сообщения'],['withdraw','Тоже отстранился / отстранилась'],['help','Предложил(а) практическую помощь'],['other','Другое']],
 recurrence:[['first','Впервые'],['sometimes','Иногда'],['often','Часто'],['always','Почти каждый конфликт']],
 helpful:[['pause','Спокойная пауза по договорённости'],['listen','Выслушать и уточнить'],['practical','Практическая помощь'],['none','Ничего из этого'],['unsure','Пока не знаю']],
 failureType:[['messages','Новые сообщения или звонки'],['apology','Повторные извинения'],['talk','Попытка продолжить разговор'],['pause','Молчание или отдаление'],['other','Другое'],['none','Ничего не ухудшало'],['unsure','Пока не знаю']],
 goal:[['talk','Вернуться к спокойному общению'],['apology','Извиниться'],['calm','Снизить напряжение'],['space','Уважить пространство'],['understand','Лучше понять ситуацию'],['pattern','Не повторять прежнюю ссору'],['other','Другое']]
};

function element<T extends HTMLElement>(selector: string): T {
  const node = document.querySelector<T>(selector);
  if (!node) throw new Error('Missing questionnaire element: ' + selector);
  return node;
}

const form = element<HTMLFormElement>('#form');
const result = element<HTMLDivElement>('#result');
const resultContent = element<HTMLDivElement>('#resultContent');
const back = element<HTMLButtonElement>('#back');
const next = element<HTMLButtonElement>('#next');
const error = element<HTMLParagraphElement>('#formError');
const bar = element<HTMLDivElement>('#bar');
const progress = element<HTMLDivElement>('.progress');
const step = element<HTMLParagraphElement>('#step');
const qs = Array.from(document.querySelectorAll<HTMLElement>('.q'));
let idx = 0;

// Keys come from this local literal, never from user text or persisted storage.
const choiceFields = Object.keys(options) as RRChoiceField[];
choiceFields.forEach(function (name) {
  const box = element<HTMLDivElement>('[data-name="' + name + '"]');
  options[name].forEach(function (pair) {
    const optionLabel = document.createElement('label');
    const input = document.createElement('input');
    optionLabel.className = 'opt';
    input.type = 'radio';
    input.name = name;
    input.value = pair[0];
    input.required = true;
    optionLabel.append(input, document.createTextNode(' ' + pair[1]));
    box.append(optionLabel);
  });
});

function value(name: string): string {
  const checked = form.querySelector<HTMLInputElement>('[name="' + name + '"]:checked');
  if (checked) return checked.value;
  const field = form.querySelector<HTMLTextAreaElement>('textarea[name="' + name + '"]');
  return field ? field.value.trim() : '';
}

function data(): RRIntake {
  return {
    age: value('age'), stage: value('stage'), situation: value('situation'),
    safety: value('safety'), boundary: value('boundary'), timing: value('timing'),
    partner: value('partner'), user: value('user'), recurrence: value('recurrence'),
    helpful: value('helpful'), success: value('success'), failureType: value('failureType'),
    failure: value('failure'), goal: value('goal')
  };
}

function label(name: RRChoiceField, selectedValue: string): string {
  const pair = options[name].find(function (choice) { return choice[0] === selectedValue; });
  return pair ? pair[1] : '';
}

function currentQuestion(): HTMLElement {
  const question = qs[idx];
  if (!question) throw new Error('Questionnaire step is unavailable.');
  return question;
}

function show(focus: boolean): void {
  qs.forEach(function (question, index) { question.classList.toggle('active', index === idx); });
  bar.style.width = ((idx + 1) / qs.length * 100) + '%';
  progress.setAttribute('aria-valuenow', String(idx + 1));
  step.textContent = 'Вопрос ' + (idx + 1) + ' из ' + qs.length;
  back.disabled = idx === 0;
  next.textContent = idx === qs.length - 1 ? 'Получить ориентир' : 'Далее';
  error.hidden = true;
  if (focus) currentQuestion().querySelector<HTMLElement>('h3')?.focus();
}

function valid(): boolean {
  const question = currentQuestion();
  return Array.from(question.querySelectorAll<HTMLElement>('[data-name]')).every(function (box) {
    return Boolean(box.dataset.name && value(box.dataset.name));
  }) && Array.from(question.querySelectorAll<HTMLTextAreaElement>('textarea[required]')).every(function (field) {
    return Boolean(field.value.trim());
  });
}

function clearSaved(): void {
  try {
    sessionStorage.removeItem('rr_case');
    sessionStorage.removeItem('rr_diag');
  } catch {
    // Storage can be unavailable; the free flow still works.
  }
}

function restrict(on: boolean): void {
  document.querySelectorAll<HTMLElement>('[data-offer]').forEach(function (offer) { offer.hidden = on; });
}

function resetDiagnostic(): void {
  clearSaved();
  form.reset();
  idx = 0;
  form.hidden = false;
  result.classList.remove('show');
  resultContent.replaceChildren();
  restrict(false);
  show(false);
}

function block(title: string, body: string, danger = false): void {
  const box = document.createElement('div');
  const heading = document.createElement('h4');
  const paragraph = document.createElement('p');
  box.className = 'block' + (danger ? ' danger' : '');
  heading.textContent = title;
  paragraph.textContent = body;
  box.append(heading, paragraph);
  resultContent.append(box);
}

function finish(d: RRIntake): void {
 const m = ResetLogic.move(d);clearSaved();form.hidden=true;result.classList.add('show');resultContent.replaceChildren();restrict(m.mode!=='STANDARD');
 if(m.mode==='SAFETY'){
  block('Сначала безопасность','В ответах отмечен или описан возможный риск. Обычный совет о примирении здесь может не подходить. Если есть непосредственная опасность, обратитесь в местные экстренные службы, когда это безопасно. По возможности свяжитесь с человеком, которому доверяете, или профильной службой помощи. Не начинайте конфронтацию ради выполнения совета из анкеты.',true);
  block('Важно','Короткая анкета не определяет степень риска и может неверно понять текст. Помощь по безопасности не требует оплаты. Ответы этой анкеты не сохранены для передачи.');
 }else if(m.mode==='MINOR'){
  block('Эта программа предназначена для взрослых','Обычный разбор доступен с 18 лет. Если вам страшно или небезопасно, обратитесь к взрослому, которому доверяете, или местной службе помощи. При непосредственной опасности — в местные экстренные службы, если это безопасно.');
 }else if(m.mode==='CLARIFY'){
  block('Сначала нужно уточнение','Вернитесь к началу и укажите, есть ли риск безопасности и какие границы контакта обозначил партнёр. До уточнения мы не предлагаем шаг к примирению.');
 }else{
  block('Ваш ориентир','Цель: '+label('goal',d.goal)+'. Граница контакта: '+label('boundary',d.boundary)+'. Это предварительный результат по ответам, а не вывод о мотивах партнёра.');
  block(m.title,m.action);block('Чего сейчас избегать',m.avoid);
  if(m.words)block('Только в уже идущем добровольном разговоре',m.words);
  block('Почему выбран этот шаг',m.why);
  var saved=false;
  try{var bytes=new Uint8Array(12);crypto.getRandomValues(bytes);var id='cis_'+Array.from(bytes,function(b){return b.toString(16).padStart(2,'0');}).join('');sessionStorage.setItem('rr_case',id);sessionStorage.setItem('rr_diag',JSON.stringify({schemaVersion:'m1-cis-v1',case_id:id,mode:'STANDARD',ts:new Date().toISOString(),data:d}));saved=true;}catch(e){clearSaved();}
  if(saved){var link=document.createElement('a');link.href='success.html';link.className='btn secondary';link.textContent='Посмотреть свою анкету';resultContent.append(link);}else block('Анкета не сохранена','Браузер не разрешил сохранить ответы для следующей страницы. Результат доступен здесь; заявка никуда не отправлена.');
  block('Хотите разбор с проверкой человеком?','Готовим 7-дневный пилот: первый разбор и до двух корректировок. Первые три покупателя — 990 ₽, следующие семь — 1 490 ₽. Оплата пока не открыта; можно запросить уведомление о старте.');
  var interest=document.createElement('a');interest.href='#offer';interest.className='btn';interest.textContent='Посмотреть формат и цену';resultContent.append(interest);
 }
 var restart=document.createElement('button');restart.type='button';restart.className='btn secondary';restart.style.marginTop='16px';restart.textContent='Удалить ответы и начать заново';restart.onclick=function(){resetDiagnostic();show(true);};resultContent.append(restart);result.focus();
}

next.onclick = function () {
  const answers = data();
  if (ResetLogic.safety(answers) || answers.age === 'minor') {
    finish(answers);
    return;
  }
  if (!valid()) {
    error.textContent = 'Ответьте на вопрос и выберите все обязательные варианты этого шага.';
    error.hidden = false;
    return;
  }
  if (idx < qs.length - 1) {
    idx++;
    show(true);
  } else {
    finish(answers);
  }
};
back.onclick = function () {
  if (idx > 0) {
    idx--;
    show(true);
  }
};
form.addEventListener('submit', function (event) {
  event.preventDefault();
  next.click();
});
element<HTMLAnchorElement>('#startDiagnostic').onclick = function () {
  resetDiagnostic();
  show(true);
};
show(false);
})();

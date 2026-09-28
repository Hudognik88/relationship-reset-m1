'use strict';

(() => {
  const options = {
    age: [['adult', 'Мне 18 или больше'], ['minor', 'Мне меньше 18']],
    safety: [['no', 'Нет'], ['yes_unsure', 'Да или не уверен(а)']],
    stage: [['under6m', 'Меньше полугода'], ['6to12m', 'От полугода до года'], ['1to3y', 'От года до трёх лет'], ['3to7y', 'От трёх до семи лет'], ['7plus', 'Больше семи лет']],
    timing: [['today', 'Сегодня'], ['yesterday', 'Вчера'], ['2to3days', '2–3 дня назад'], ['older', 'Раньше']],
    boundary: [['clear', 'Готов(а) общаться'], ['space', 'Просит время или пространство'], ['no_contact', 'Просит не связываться'], ['unsure', 'Пока непонятно']],
    partner: [['talk', 'Пытается поговорить'], ['quiet', 'Молчит'], ['angry', 'Злится'], ['space', 'Просит паузу'], ['distant', 'Держится отстранённо'], ['left', 'Ушёл / ушла'], ['other', 'Другое']],
    user: [['talk', 'Пытался / пыталась поговорить'], ['apology', 'Извинился / извинилась'], ['defend', 'Объяснял(а) свою позицию'], ['space', 'Дал(а) время и пространство'], ['messages', 'Отправлял(а) сообщения'], ['withdraw', 'Замкнулся / замкнулась'], ['help', 'Предложил(а) помощь'], ['other', 'Другое']],
    recurrence: [['first', 'Впервые'], ['sometimes', 'Иногда'], ['often', 'Часто'], ['always', 'Почти каждый раз']],
    helpful: [['pause', 'Пауза'], ['listen', 'Выслушать друг друга'], ['practical', 'Практическая помощь'], ['none', 'Ничего не помогало'], ['unsure', 'Пока не знаю']],
    failureType: [['messages', 'Новые сообщения'], ['apology', 'Повторные извинения'], ['talk', 'Попытка поговорить'], ['pause', 'Пауза'], ['other', 'Другое'], ['none', 'Такой попытки не было'], ['unsure', 'Пока не знаю']],
    goal: [['talk', 'Как начать разговор'], ['apology', 'Как выразить сожаление'], ['calm', 'Как снизить напряжение'], ['space', 'Как дать пространство'], ['understand', 'Как понять случившееся'], ['pattern', 'Почему сценарий повторяется'], ['other', 'Другое']]
  };
  const labels = { age: 'Возраст', stage: 'Продолжительность отношений', safety: 'Угрозы и безопасность', boundary: 'Границы контакта', timing: 'Когда произошла ссора', partner: 'Реакция партнёра', user: 'Действия участника', recurrence: 'Повторяемость', helpful: 'Что помогало', failureType: 'Что не помогло', goal: 'Что хочется прояснить', situation: 'Описание ситуации', success: 'Пример удачной попытки', failure: 'Что произошло после неудачной попытки' };
  const answerOrder = ['situation', 'age', 'stage', 'safety', 'boundary', 'timing', 'partner', 'user', 'recurrence', 'goal', 'helpful', 'success', 'failureType', 'failure'];
  const $ = (id) => document.getElementById(id);
  let csrf = null, busy = false, selectedId = null, version = null, baseline = '', dirty = false, pendingAction = null;
  let sessionEpoch = 0, sessionTimer = null;
  const requests = new Set();
  let operationId = 0;
  class ApiError extends Error { constructor(status, code) { super(code); this.status = status; this.code = code; } }
  function message(id, text) { $(id).textContent = text || ''; $(id).hidden = !text; }
  function setBusy(value, token) {
    if (value) operationId += 1;
    else if (token !== undefined && token !== operationId) return;
    else if (token === undefined) operationId += 1;
    busy = value;
    document.querySelectorAll('button,input,select,textarea').forEach((element) => { element.disabled = value; });
    $('workspace').setAttribute('aria-busy', String(value)); $('login-form').setAttribute('aria-busy', String(value));
    return operationId;
  }
  function dateLabel(value) {
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' }).format(date) : 'Дата не указана';
  }
  function acceptSession(session) {
    if (session.authenticated !== true || typeof session.csrf_token !== 'string' || !Number.isFinite(new Date(session.expires_at).getTime())) throw new ApiError(0, 'invalid_response');
    if (csrf !== session.csrf_token) clearPublicationConfirmation();
    csrf = session.csrf_token;
    window.clearTimeout(sessionTimer);
    sessionTimer = window.setTimeout(expireSession, Math.max(0, new Date(session.expires_at).getTime() - Date.now()));
    $('session-info').textContent = `Вход действует до ${dateLabel(session.expires_at)}. Сеанс — 1 час.`;
    $('login-view').hidden = true; $('workspace').hidden = false;
  }
  async function request(path, method = 'GET', body) {
    const epoch = sessionEpoch;
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && !(path === '/owner/api/session' && method === 'POST')) {
      if (!csrf) throw new ApiError(401, 'unauthorized');
      headers['X-CSRF-Token'] = csrf;
    }
    const controller = new AbortController();
    requests.add(controller);
    const timeout = window.setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(path, { method, headers, signal: controller.signal, credentials: 'same-origin', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer', ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
      let result;
      try { result = await response.json(); } catch (_) { throw new ApiError(response.status, 'invalid_response'); }
      if (epoch !== sessionEpoch) throw new ApiError(0, 'stale_session');
      if (!response.ok) throw new ApiError(response.status, result?.error || 'unknown');
      return result;
    } catch (error) { if (epoch !== sessionEpoch) throw new ApiError(0, 'stale_session'); if (error instanceof ApiError) throw error; throw new ApiError(0, 'network'); }
    finally { window.clearTimeout(timeout); requests.delete(controller); }
  }
  function clearInvitation() {
    $('client-invitation').value = ''; $('invite-result').hidden = true; $('invite-expiry').textContent = '';
    $('create-invitation-button').hidden = false; message('invite-error', '');
  }
  function clearPublicationConfirmation() {
    $('reviewed-confirmation').checked = false;
    pendingAction = null; $('unsaved-warning').hidden = true;
  }
  async function handleError(error, target = 'global-message') {
    if (error.code === 'stale_session') return;
    if (error.status === 401 && error.code !== 'owner_invitation_invalid') {
      expireSession(); return;
    }
    if (error.status === 403) {
      try { acceptSession(await request('/owner/api/session')); }
      catch (refreshError) { if (refreshError.code === 'stale_session') return; if (refreshError.status === 401) return handleError(refreshError, target); }
      message(target, 'Не удалось подтвердить действие. Повторите его ещё раз. Черновик сохранён в открытой вкладке.'); return;
    }
    if (error.code === 'review_conflict') {
      $('conflict-warning').hidden = false;
      message('review-error', 'Публикация остановлена: появилась другая версия разбора. Ваш текст не изменён.');
      $('conflict-warning').querySelector('button').focus(); return;
    }
    message(target, error.code === 'owner_invitation_invalid' ? 'Приглашение автора не подошло или истекло. Получите новый код на сервере.'
      : error.status === 429 ? 'Слишком много действий подряд. Подождите немного и попробуйте снова.'
      : error.status === 404 ? 'Эта анкета больше недоступна. Обновите список. Неопубликованный текст остался в редакторе.'
      : error.status === 422 ? 'Проверьте текст и подтверждение проверки. Разбор должен содержать от 1 до 4000 символов.'
      : 'Не удалось завершить действие. Проверьте соединение и попробуйте снова. Текст в редакторе не изменён.');
  }
  function expireSession() {
    sessionEpoch += 1; requests.forEach((controller) => controller.abort()); window.clearTimeout(sessionTimer);
    csrf = null; clearInvitation(); clearPublicationConfirmation(); $('workspace').hidden = true; $('login-view').hidden = false; $('invitation').value = ''; setBusy(false);
    message('login-error', dirty ? 'Вход закончился. Получите новое приглашение автора. Неопубликованный текст пока остался в этой вкладке.' : 'Вход закончился. Получите новое приглашение автора и войдите снова.');
    $('login-heading').focus();
  }
  function guard(action, losesCode = false) {
    if (busy) return;
    if (!dirty && !(losesCode && $('client-invitation').value)) { action(); return; }
    $('unsaved-heading').textContent = dirty ? 'Есть неопубликованные изменения' : 'Сохраните клиентское приглашение';
    $('unsaved-warning').querySelector('p').textContent = dirty ? 'Если продолжить, текст в редакторе будет заменён или очищен. Скопируйте его и показанный код приглашения, если хотите сохранить.' : 'Код показывается один раз. Скопируйте его перед выходом: получить этот же код снова не получится.';
    pendingAction = action; $('unsaved-warning').hidden = false; $('unsaved-heading').focus();
  }
  function updateDraftState() {
    dirty = $('review-text').value !== baseline;
    $('draft-state').textContent = dirty ? 'Не опубликовано' : 'Без изменений';
    $('draft-state').classList.toggle('dirty', dirty);
    $('review-count').textContent = `${$('review-text').value.length} / 4000`;
    $('review-preview').textContent = $('review-text').value || 'Здесь появится ваш текст.';
  }
  function clearEditor() {
    selectedId = null; version = null; baseline = ''; dirty = false; pendingAction = null;
    $('review-form').reset(); $('review-text').value = ''; $('review-preview').textContent = '';
    $('answers').replaceChildren(); $('case-heading').textContent = 'Тестовый пример'; $('case-date').textContent = '';
    $('editor-panel').hidden = true; $('empty-editor').hidden = false;
    $('conflict-warning').hidden = true; $('unsaved-warning').hidden = true; message('review-error', '');
    updateDraftState();
  }
  function clearPrivateState() {
    clearEditor(); clearInvitation(); $('case-list').replaceChildren(); $('invitation').value = ''; $('session-info').textContent = '';
    ['global-message', 'queue-error', 'login-error', 'review-error'].forEach((id) => message(id, ''));
    csrf = null;
    sessionEpoch += 1; requests.forEach((controller) => controller.abort()); window.clearTimeout(sessionTimer);
  }
  async function loadQueue() {
    const result = await request('/owner/api/cases');
    if (!Array.isArray(result.cases)) throw new ApiError(0, 'invalid_response');
    $('case-list').replaceChildren(); $('queue-empty').hidden = result.cases.length > 0;
    result.cases.slice(0, 20).forEach((item) => {
      if (typeof item.id !== 'string' || !/^[a-f0-9]{32}$/.test(item.id)) return;
      const button = document.createElement('button'); button.type = 'button'; button.className = 'case-card'; button.dataset.caseId = item.id;
      if (item.id === selectedId) button.setAttribute('aria-current', 'true');
      const title = document.createElement('strong'); title.textContent = `Пример · ${item.id.slice(-8)}`;
      const date = document.createElement('time'); date.textContent = dateLabel(item.created_at); date.dateTime = item.created_at || '';
      const status = document.createElement('span'); status.className = item.status === 'published' ? 'queue-status published' : 'queue-status'; status.textContent = item.status === 'published' ? 'Разбор опубликован' : 'Ожидает проверки';
      button.append(title, date, status); button.addEventListener('click', () => { if (selectedId !== item.id) guard(() => openCase(item.id)); });
      $('case-list').append(button);
    });
    message('queue-error', '');
  }
  function renderCase(result, id) {
    if (!result.case || result.case.id !== id || typeof result.review_version !== 'string') throw new ApiError(0, 'invalid_response');
    selectedId = id; version = result.review_version;
    $('case-heading').textContent = `Пример · ${id.slice(-8)}`; $('case-date').textContent = `Получен ${dateLabel(result.case.created_at)}`;
    const published = result.review && typeof result.review.text === 'string';
    $('case-status').textContent = published ? 'Опубликован' : 'На проверке'; $('case-status').classList.toggle('ready', Boolean(published));
    $('answers').replaceChildren();
    const questionnaire = result.case.questionnaire || {};
    answerOrder.forEach((name) => {
      const item = document.createElement('div'); item.className = ['situation', 'success', 'failure'].includes(name) ? 'answer-item wide' : 'answer-item';
      const question = document.createElement('dt'); question.textContent = labels[name];
      const answer = document.createElement('dd');
      const value = questionnaire[name];
      answer.textContent = options[name] ? (options[name].find(([key]) => key === value)?.[1] || 'Не указано') : (typeof value === 'string' && value ? value : 'Не указано');
      item.append(question, answer); $('answers').append(item);
    });
    baseline = published ? result.review.text : ''; $('review-text').value = baseline;
    $('reviewed-confirmation').checked = false; $('conflict-warning').hidden = true; $('preview-details').open = false;
    $('publish-button').textContent = published ? 'Опубликовать изменения' : 'Опубликовать разбор';
    $('publication-note').textContent = published ? 'Новая версия заменит опубликованный текст в кабинете участника.' : 'После публикации текст появится в кабинете участника.';
    updateDraftState(); message('review-error', ''); $('editor-panel').hidden = false; $('empty-editor').hidden = true;
    document.querySelectorAll('.case-card').forEach((button) => { if (button.dataset.caseId === id) button.setAttribute('aria-current', 'true'); else button.removeAttribute('aria-current'); });
    $('case-heading').focus();
  }
  async function openCase(id) {
    if (busy) return;
    const operation = setBusy(true); message('global-message', '');
    try { renderCase(await request('/owner/api/cases/' + id), id); }
    catch (error) { await handleError(error); }
    finally { setBusy(false, operation); }
  }
  async function logout() {
    if (busy) return;
    const operation = setBusy(true);
    try {
      try { await request('/owner/api/session', 'DELETE'); } catch (error) { if (error.status !== 401) throw error; }
      clearPrivateState(); $('workspace').hidden = true; $('login-view').hidden = false;
      message('global-message', 'Вы вышли из кабинета автора. Неопубликованный текст и показанные коды очищены из этой вкладки.'); $('login-heading').focus();
    } catch (error) { await handleError(error); }
    finally { setBusy(false, operation); }
  }
  $('login-form').addEventListener('submit', async (event) => {
    event.preventDefault(); if (busy) return;
    const invitation = $('invitation').value.trim();
    if (!invitation) { message('login-error', 'Введите приглашение автора.'); $('invitation').focus(); return; }
    const operation = setBusy(true); message('login-error', ''); message('global-message', ''); $('login-button').textContent = 'Открываем…';
    try {
      try { acceptSession(await request('/owner/api/session', 'POST', { invitation, synthetic: true })); }
      catch (error) { if (error.code === 'session_exists') acceptSession(await request('/owner/api/session')); else throw error; }
      $('invitation').value = ''; await loadQueue();
      if (dirty) message('global-message', 'Вход восстановлен. Неопубликованный текст остался в редакторе.');
    } catch (error) { await handleError(error, $('workspace').hidden ? 'login-error' : 'queue-error'); }
    finally { if (setBusy(false, operation) !== undefined) $('login-button').textContent = 'Войти в кабинет →'; }
  });
  $('refresh-queue-button').addEventListener('click', async () => {
    if (busy) return;
    const operation = setBusy(true); message('global-message', '');
    try { await loadQueue(); message('global-message', 'Список обновлён. Текст в редакторе не изменён.'); }
    catch (error) { await handleError(error, 'queue-error'); }
    finally { setBusy(false, operation); }
  });
  $('review-text').addEventListener('input', () => { updateDraftState(); $('reviewed-confirmation').checked = false; $('review-text').removeAttribute('aria-invalid'); });
  $('review-form').addEventListener('submit', async (event) => {
    event.preventDefault(); if (busy || !selectedId) return;
    const text = $('review-text').value.trim();
    if (!text || text.length > 4000) { message('review-error', 'Добавьте текст разбора — от 1 до 4000 символов.'); $('review-text').setAttribute('aria-invalid', 'true'); $('review-text').focus(); return; }
    if (!$('reviewed-confirmation').checked) { message('review-error', 'Подтвердите, что вы прочитали анкету и проверили текст.'); $('reviewed-confirmation').focus(); return; }
    const operation = setBusy(true); message('review-error', ''); message('global-message', ''); $('publish-button').textContent = 'Публикуем…';
    try {
      const result = await request('/owner/api/reviews', 'POST', { case_id: selectedId, text, synthetic: true, reviewed: true, expected_version: version });
      if (result.published !== true || result.case_id !== selectedId || typeof result.review_version !== 'string') throw new ApiError(0, 'invalid_response');
      version = result.review_version; baseline = text; $('review-text').value = text; updateDraftState(); $('reviewed-confirmation').checked = false;
      $('conflict-warning').hidden = true; $('case-status').textContent = 'Опубликован'; $('case-status').classList.add('ready');
      $('publication-note').textContent = 'Новая версия заменит опубликованный текст в кабинете участника.';
      message('global-message', 'Разбор опубликован. Тестовый участник увидит его в своём кабинете.');
      try { await loadQueue(); } catch (error) { await handleError(error, 'queue-error'); }
    } catch (error) { await handleError(error, 'review-error'); }
    finally { if (setBusy(false, operation) !== undefined) $('publish-button').textContent = version === 'unpublished' ? 'Опубликовать разбор' : 'Опубликовать изменения'; }
  });
  $('reload-case-button').addEventListener('click', () => { if (selectedId) guard(() => openCase(selectedId)); });
  $('create-invitation-button').addEventListener('click', async () => {
    if (busy) return;
    const operation = setBusy(true); message('invite-error', '');
    try {
      const result = await request('/owner/api/invitations', 'POST', { synthetic: true });
      if (typeof result.invitation !== 'string' || !result.invitation) throw new ApiError(0, 'invalid_response');
      $('client-invitation').value = result.invitation; $('invite-result').hidden = false; $('create-invitation-button').hidden = true;
      $('invite-expiry').textContent = `Действует до ${dateLabel(result.expires_at)}.`; $('client-invitation').focus();
    } catch (error) { await handleError(error, 'invite-error'); }
    finally { setBusy(false, operation); }
  });
  $('copy-invitation-button').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText($('client-invitation').value); message('invite-error', 'Код скопирован. Сохраните его перед закрытием.'); }
    catch (_) { $('client-invitation').focus(); $('client-invitation').select(); message('invite-error', 'Скопируйте выделенный код вручную.'); }
  });
  $('hide-invitation-button').addEventListener('click', clearInvitation);
  $('logout-button').addEventListener('click', () => guard(logout, true));
  $('brand-link').addEventListener('click', (event) => { event.preventDefault(); guard(() => { dirty = false; clearInvitation(); window.location.assign('/owner/'); }, true); });
  $('keep-editing-button').addEventListener('click', () => { pendingAction = null; $('unsaved-warning').hidden = true; $('review-text').focus(); });
  $('discard-button').addEventListener('click', () => { const action = pendingAction; pendingAction = null; $('unsaved-warning').hidden = true; if (action) action(); });
  window.addEventListener('beforeunload', (event) => { if (dirty || $('client-invitation').value) { event.preventDefault(); event.returnValue = ''; } });
  async function resume() {
    if (busy) return;
    const operation = setBusy(true);
    try { acceptSession(await request('/owner/api/session')); await loadQueue(); }
    catch (error) {
      if (error.status === 401 && !csrf && !dirty) { $('workspace').hidden = true; $('login-view').hidden = false; }
      else await handleError(error, $('workspace').hidden ? 'login-error' : 'queue-error');
    } finally { setBusy(false, operation); }
  }
  window.addEventListener('pagehide', () => { sessionEpoch += 1; requests.forEach((controller) => controller.abort()); csrf = null; clearInvitation(); clearPublicationConfirmation(); $('invitation').value = ''; window.clearTimeout(sessionTimer); $('workspace').hidden = true; setBusy(false); });
  window.addEventListener('pageshow', (event) => { if (event.persisted) resume(); });
  resume();
})();

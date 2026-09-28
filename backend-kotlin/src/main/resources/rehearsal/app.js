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
  const $ = (id) => document.getElementById(id);
  const views = ['login-view', 'form-view', 'stop-view', 'case-view'];
  const stepNames = ['Перед началом', 'Что произошло', 'Реакции и цель', 'Что уже пробовали'];
  const form = $('case-form');
  let csrfToken = null;
  let step = 0;
  let busy = false;
  let pendingPayload = null;
  let currentView = 'login-view';
  let hadSession = false;
  let loginMode = 'invitation';
  let hasAccessKey = false;
  let keyUncertain = false;
  let keySavedHere = false;
  let sessionTimer = null;
  let sessionEpoch = 0;
  const requests = new Set();
  let operationId = 0;
  let sessionIdentity = null;

  class ApiError extends Error {
    constructor(status, code) { super(code); this.status = status; this.code = code; }
  }

  document.querySelectorAll('[data-options]').forEach((select) => {
    const empty = document.createElement('option');
    empty.value = ''; empty.textContent = 'Выберите вариант'; empty.disabled = true; empty.selected = true;
    select.append(empty);
    options[select.dataset.options].forEach(([value, text]) => {
      const option = document.createElement('option'); option.value = value; option.textContent = text; select.append(option);
    });
  });
  document.querySelectorAll('[data-choices]').forEach((container) => {
    options[container.dataset.choices].forEach(([value, text]) => {
      const label = document.createElement('label'); label.className = 'choice';
      const input = document.createElement('input'); input.type = 'radio'; input.name = container.dataset.choices; input.value = value; input.required = true;
      const span = document.createElement('span'); span.textContent = text;
      label.append(input, span); container.append(label);
    });
  });

  function message(id, text) {
    $(id).textContent = text || '';
    $(id).hidden = !text;
  }
  function showView(id, focus = true) {
    currentView = id;
    views.forEach((view) => { $(view).hidden = view !== id; });
    $('access-panel').hidden = id === 'login-view';
    if (focus) $(id).querySelector('h2:not([hidden])')?.focus();
  }
  function setBusy(value, token) {
    if (value) operationId += 1;
    else if (token !== undefined && token !== operationId) return;
    else if (token === undefined) operationId += 1;
    busy = value;
    document.querySelectorAll('button').forEach((button) => { button.disabled = value; });
    document.querySelectorAll('form input, form select, form textarea').forEach((input) => { input.disabled = value; });
    $(currentView).setAttribute('aria-busy', String(value));
    return operationId;
  }
  function updateStep(focus = true) {
    document.querySelectorAll('.form-step').forEach((section, index) => { section.hidden = index !== step; });
    document.querySelectorAll('.step-track li').forEach((item, index) => {
      item.classList.toggle('current', index === step); item.classList.toggle('complete', index < step);
      if (index === step) item.setAttribute('aria-current', 'step'); else item.removeAttribute('aria-current');
    });
    $('step-count').textContent = `Шаг ${step + 1} из 4`;
    $('step-caption').textContent = stepNames[step];
    $('back-button').hidden = step === 0;
    $('next-button').textContent = step === 3 ? 'Отправить тестовый пример' : 'Продолжить →';
    message('form-error', '');
    if (focus) document.querySelector(`.form-step[data-step="${step}"] h2`).focus();
  }
  function validStep() {
    const controls = [...document.querySelector(`.form-step[data-step="${step}"]`).querySelectorAll('input,select,textarea')];
    controls.forEach((input) => input.removeAttribute('aria-invalid'));
    const invalid = controls.find((input) => !input.checkValidity() || (input.required && input.tagName === 'TEXTAREA' && !input.value.trim()));
    if (!invalid) return true;
    invalid.setAttribute('aria-invalid', 'true');
    message('form-error', invalid.type === 'checkbox' ? 'Подтвердите, что ответы вымышлены, прежде чем отправить пример.' : 'Ответьте на все обязательные вопросы этого шага.');
    invalid.focus(); return false;
  }
  function stop(reason) {
    $('stop-reason').textContent = reason === 'adult_required'
      ? 'Тестовый кабинет предназначен только для совершеннолетних участников.'
      : 'Этот тестовый формат не предназначен для ситуаций, где есть угрозы, принуждение или сомнения в безопасности.';
    showView('stop-view');
  }

  async function request(path, method = 'GET', body) {
    const epoch = sessionEpoch;
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if ((method !== 'GET' && !['/client/session', '/client/login'].includes(path)) || method === 'DELETE') {
      if (!csrfToken) throw new ApiError(401, 'unauthorized');
      headers['X-CSRF-Token'] = csrfToken;
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
    } catch (error) {
      if (epoch !== sessionEpoch) throw new ApiError(0, 'stale_session');
      if (error instanceof ApiError) throw error;
      throw new ApiError(0, 'network');
    } finally { window.clearTimeout(timeout); requests.delete(controller); }
  }
  function acceptSession(session) {
    if (session.authenticated !== true || typeof session.csrf_token !== 'string') throw new ApiError(0, 'invalid_response');
    const expiresAt = new Date(session.expires_at);
    if (!Number.isFinite(expiresAt.getTime())) throw new ApiError(0, 'invalid_response');
    if (sessionIdentity && sessionIdentity !== session.csrf_token) {
      clearPreviousWorkspace(); keyUncertain = false; keySavedHere = false;
    }
    sessionIdentity = session.csrf_token;
    csrfToken = session.csrf_token; hadSession = true;
    hasAccessKey = session.has_access_key === true;
    updateAccessStatus();
    window.clearTimeout(sessionTimer);
    sessionTimer = window.setTimeout(expireSession, Math.max(0, expiresAt.getTime() - Date.now()));
    const expiresLabel = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' }).format(expiresAt);
    document.querySelectorAll('.session-info').forEach((element) => {
      element.textContent = `Вход действует 24 часа: до ${expiresLabel}. Время указано для вашего браузера.`;
    });
  }
  async function handleError(error, target) {
    if (error.code === 'stale_session') return;
    if (error.code === 'adult_required' || error.code === 'safety_not_supported') { stop(error.code); return; }
    if (error.status === 401 && !['invitation_invalid', 'access_key_invalid'].includes(error.code)) {
      expireSession();
      return;
    }
    if (error.status === 403) {
      try { acceptSession(await request('/client/session')); }
      catch (refreshError) { if (refreshError.code === 'stale_session') return; if (refreshError.status === 401) return handleError(refreshError, target); }
      message(target, 'Не удалось подтвердить действие. Повторите его ещё раз. Ваши неотправленные ответы остались в этой вкладке.');
      return;
    }
    const text = error.code === 'invitation_invalid' ? 'Не удалось принять приглашение. Проверьте код или попросите организатора выдать новое.'
      : error.code === 'access_key_invalid' ? 'Ключ не подошёл. Проверьте, что он скопирован полностью. Если вы заменяли ключ, используйте новый.'
      : error.status === 429 ? 'Слишком много попыток за короткое время. Подождите немного и попробуйте снова.'
      : error.code === 'idempotency_conflict' ? 'Эта попытка уже была сохранена с другими ответами. Проверьте сохранённый пример перед повторной отправкой.'
      : error.status === 422 ? 'Не удалось принять ответы. Проверьте обязательные поля и длину текста. Используйте только вымышленные данные.'
      : 'Сейчас не удалось завершить действие. Проверьте соединение и попробуйте ещё раз. Неотправленные ответы остались в этой вкладке.';
    message(target, text);
  }
  function questionnaire() {
    const data = new FormData(form);
    const result = {};
    [...Object.keys(options), 'situation', 'success', 'failure'].forEach((name) => { result[name] = data.get(name) || ''; });
    return result;
  }
  function renderCase(result, focus = true) {
    if (!result || !result.case) {
      showView('form-view', false); updateStep(focus); return false;
    }
    pendingPayload = null;
    const review = result.review && typeof result.review.text === 'string' ? result.review : null;
    $('waiting-content').hidden = Boolean(review); $('review-content').hidden = !review;
    $('case-view').setAttribute('aria-labelledby', review ? 'review-heading' : 'case-heading');
    $('review-text').textContent = review?.text || '';
    $('saved-situation').textContent = result.case.questionnaire?.situation || '';
    $('refresh-button').textContent = review ? 'Обновить кабинет' : 'Проверить, появился ли разбор';
    $('delete-confirmation').hidden = true; $('delete-button').hidden = false;
    message('case-error', '');
    showView('case-view', false);
    if (focus) $(review ? 'review-heading' : 'case-heading').focus();
    return true;
  }
  function resetForm() {
    form.reset(); pendingPayload = null; step = 0; updateStep(false);
    ['situation', 'success', 'failure'].forEach((name) => { $(`${name}-count`).textContent = `0 / ${$(name).maxLength}`; });
    document.querySelectorAll('[aria-invalid]').forEach((input) => input.removeAttribute('aria-invalid'));
  }

  function setLoginMode(mode) {
    loginMode = mode;
    $('invitation-mode').setAttribute('aria-pressed', String(mode === 'invitation'));
    $('access-mode').setAttribute('aria-pressed', String(mode === 'access'));
    $('invitation-field').hidden = mode !== 'invitation'; $('access-login-field').hidden = mode !== 'access';
    $('invitation').required = mode === 'invitation'; $('access-login').required = mode === 'access';
    $('login-description').textContent = mode === 'invitation' ? 'Введите код приглашения, чтобы открыть свой тестовый кабинет.' : 'Введите сохранённый ключ, чтобы вернуться к своей анкете и разбору.';
    message('login-error', '');
  }
  function updateAccessStatus() {
    $('create-access-button').textContent = hasAccessKey ? 'Заменить ключ' : 'Сохранить ключ кабинета';
    $('access-status').textContent = keyUncertain ? 'Перед выходом получите и сохраните новый ключ. Прежний ключ уже мог перестать работать.' : hasAccessKey ? 'Ключ создан. Храните его у себя: он открывает этот кабинет.' : 'Сохраните ключ перед выходом. Без него вернуться в кабинет не получится.';
  }
  function clearPrivateKey() {
    $('access-key').value = ''; $('access-result').hidden = true;
    $('rotate-warning').hidden = true; $('logout-warning').hidden = true;
    $('create-access-button').hidden = false; message('access-error', '');
  }
  function clearPreviousWorkspace() {
    resetForm(); clearPrivateKey(); $('review-text').textContent = ''; $('saved-situation').textContent = '';
    document.querySelectorAll('.session-info').forEach((element) => { element.textContent = ''; });
  }
  function expireSession() {
    sessionEpoch += 1; requests.forEach((controller) => controller.abort()); window.clearTimeout(sessionTimer);
    csrfToken = null; clearPreviousWorkspace(); setBusy(false);
    setLoginMode('access'); showView('login-view');
    message('login-error', 'Вход в кабинет закончился. Введите сохранённый ключ, чтобы вернуться. Неотправленные ответы очищены из этой вкладки.');
  }
  async function createAccessKey() {
    if (busy) return;
    const operation = setBusy(true); message('access-error', ''); keyUncertain = true; keySavedHere = false; updateAccessStatus();
    try {
      const result = await request('/client/access-key', 'POST', { synthetic: true });
      if (typeof result.access_key !== 'string' || !result.access_key) throw new ApiError(0, 'invalid_response');
      hasAccessKey = true; updateAccessStatus();
      $('access-key').value = result.access_key; $('access-result').hidden = false;
      $('access-status').textContent = 'Новый ключ показан ниже. Сохраните его прежде, чем закрывать вкладку.';
      $('create-access-button').hidden = true; $('rotate-warning').hidden = true; $('logout-warning').hidden = true;
      $('access-key').focus();
    } catch (error) {
      await handleError(error, 'access-error');
      if (csrfToken && error.code !== 'stale_session') message('access-error', 'Не удалось получить ключ. Прежний уже мог перестать работать. Оставайтесь в кабинете и создайте новый ключ, затем сохраните его.');
    }
    finally { setBusy(false, operation); }
  }
  $('invitation-mode').addEventListener('click', () => { setLoginMode('invitation'); $('invitation').focus(); });
  $('access-mode').addEventListener('click', () => { setLoginMode('access'); $('access-login').focus(); });
  $('create-access-button').addEventListener('click', () => {
    if (hasAccessKey) { $('rotate-warning').hidden = false; $('rotate-heading').focus(); }
    else createAccessKey();
  });
  $('cancel-rotate-button').addEventListener('click', () => { $('rotate-warning').hidden = true; $('create-access-button').focus(); });
  $('confirm-rotate-button').addEventListener('click', createAccessKey);
  $('copy-access-button').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText($('access-key').value); message('access-error', 'Ключ скопирован. Сохраните его у себя.'); }
    catch (_) { $('access-key').focus(); $('access-key').select(); message('access-error', 'Выделите ключ и скопируйте его вручную.'); }
  });
  $('saved-access-button').addEventListener('click', () => { keyUncertain = false; keySavedHere = true; clearPrivateKey(); updateAccessStatus(); message('global-message', 'Ключ скрыт. Для повторного входа используйте сохранённую копию.'); });

  $('login-form').addEventListener('submit', async (event) => {
    event.preventDefault(); if (busy) return;
    const input = loginMode === 'invitation' ? $('invitation') : $('access-login');
    const credential = input.value.trim();
    if (!credential) { message('login-error', loginMode === 'invitation' ? 'Введите код приглашения.' : 'Введите ключ кабинета.'); input.focus(); return; }
    message('login-error', ''); message('global-message', ''); const operation = setBusy(true);
    $('login-button').textContent = 'Открываем…';
    try {
      try {
        const session = await request(loginMode === 'invitation' ? '/client/session' : '/client/login', 'POST', loginMode === 'invitation' ? { invitation: credential, synthetic: true } : { access_key: credential, synthetic: true });
        clearPreviousWorkspace(); sessionIdentity = null; keyUncertain = false; keySavedHere = loginMode === 'access'; acceptSession(session);
      }
      catch (error) { if (error.code === 'session_exists') acceptSession(await request('/client/session')); else throw error; }
      $('invitation').value = ''; $('access-login').value = '';
      showView('form-view', false); updateStep(false);
      renderCase(await request('/client/case'));
    } catch (error) { await handleError(error, currentView === 'login-view' ? 'login-error' : 'global-message'); }
    finally { if (setBusy(false, operation) !== undefined) $('login-button').textContent = 'Открыть кабинет →'; }
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault(); if (busy || !validStep()) return;
    if (step === 0) {
      const data = questionnaire();
      if (data.age !== 'adult') { stop('adult_required'); return; }
      if (data.safety !== 'no') { stop('safety_not_supported'); return; }
    }
    if (step < 3) { step += 1; updateStep(); return; }
    const data = questionnaire();
    if (data.age !== 'adult') { stop('adult_required'); return; }
    if (data.safety !== 'no') { stop('safety_not_supported'); return; }
    message('form-error', ''); const operation = setBusy(true); $('next-button').textContent = 'Сохраняем…';
    try {
      // Resolve an earlier uncertain write before assigning a new request ID to edited answers.
      if (pendingPayload && JSON.stringify(pendingPayload.questionnaire) !== JSON.stringify(data)) {
        if (renderCase(await request('/client/case'))) return;
        pendingPayload = null;
      }
      if (!pendingPayload) pendingPayload = { schema_version: 'm1-cis-v1', synthetic: true, client_request_id: crypto.randomUUID(), questionnaire: data };
      try { await request('/client/case', 'POST', pendingPayload); }
      catch (error) { if (error.code !== 'case_exists') throw error; }
      renderCase(await request('/client/case'));
    } catch (error) {
      if (error.status === 422) pendingPayload = null;
      await handleError(error, 'form-error');
    } finally { if (setBusy(false, operation) !== undefined) $('next-button').textContent = 'Отправить тестовый пример'; }
  });
  $('back-button').addEventListener('click', () => { if (!busy && step > 0) { step -= 1; updateStep(); } });
  $('gate-back-button').addEventListener('click', () => { step = 0; showView('form-view', false); updateStep(); });
  ['situation', 'success', 'failure'].forEach((name) => {
    $(name).addEventListener('input', () => { $(`${name}-count`).textContent = `${$(name).value.length} / ${$(name).maxLength}`; });
  });
  form.addEventListener('input', (event) => { event.target.removeAttribute('aria-invalid'); });

  $('refresh-button').addEventListener('click', async () => {
    if (busy) return;
    const operation = setBusy(true); message('case-error', ''); message('global-message', '');
    try { const result = await request('/client/case'); renderCase(result, false); message('global-message', result.review ? 'Кабинет обновлён.' : 'Пока без изменений. Разбор появится после проверки.'); }
    catch (error) { await handleError(error, 'case-error'); }
    finally { setBusy(false, operation); }
  });
  $('delete-button').addEventListener('click', () => { $('delete-confirmation').hidden = false; $('delete-button').hidden = true; $('delete-heading').focus(); });
  $('cancel-delete-button').addEventListener('click', () => { $('delete-confirmation').hidden = true; $('delete-button').hidden = false; $('delete-button').focus(); });
  $('confirm-delete-button').addEventListener('click', async () => {
    if (busy) return;
    const operation = setBusy(true); message('case-error', '');
    try { await request('/client/case', 'DELETE'); resetForm(); $('review-text').textContent = ''; $('saved-situation').textContent = ''; showView('form-view', false); updateStep(); message('global-message', 'Тестовый пример удалён. Можно начать новый.'); }
    catch (error) { await handleError(error, 'case-error'); }
    finally { setBusy(false, operation); }
  });
  async function logout() {
    if (busy) return;
    const operation = setBusy(true); message('global-message', '');
    try {
      try { await request('/client/session', 'DELETE'); } catch (error) { if (error.status !== 401) throw error; }
      sessionEpoch += 1; window.clearTimeout(sessionTimer); csrfToken = null; sessionIdentity = null; hadSession = false; hasAccessKey = false; keyUncertain = false; keySavedHere = false; clearPreviousWorkspace(); $('invitation').value = ''; $('access-login').value = '';
      setLoginMode('access');
      showView('login-view'); message('login-error', ''); message('global-message', 'Вы вышли из кабинета. Выход не удаляет уже отправленный тестовый пример.');
    } catch (error) { await handleError(error, 'global-message'); }
    finally { setBusy(false, operation); }
  }
  document.querySelectorAll('.logout-button').forEach((button) => button.addEventListener('click', () => {
    if (!hasAccessKey || keyUncertain || !keySavedHere || $('access-key').value) { $('logout-warning').hidden = false; $('logout-heading').focus(); }
    else logout();
  }));
  $('cancel-logout-button').addEventListener('click', () => { $('logout-warning').hidden = true; $('create-access-button').focus(); });
  $('confirm-logout-button').addEventListener('click', logout);

  async function resume() {
    if (busy) return;
    const operation = setBusy(true);
    try {
      acceptSession(await request('/client/session'));
      if (currentView === 'login-view') { showView('form-view', false); updateStep(false); }
      renderCase(await request('/client/case'), false);
    }
    catch (error) {
      if (error.status === 401 && !hadSession) { csrfToken = null; showView('login-view', false); }
      else await handleError(error, currentView === 'login-view' ? 'login-error' : 'global-message');
    } finally { setBusy(false, operation); }
  }
  updateStep(false);
  window.addEventListener('beforeunload', (event) => { if (keyUncertain || $('access-key').value) { event.preventDefault(); event.returnValue = ''; } });
  window.addEventListener('pagehide', () => { sessionEpoch += 1; requests.forEach((controller) => controller.abort()); clearPreviousWorkspace(); showView('login-view', false); $('invitation').value = ''; $('access-login').value = ''; window.clearTimeout(sessionTimer); csrfToken = null; setBusy(false); });
  window.addEventListener('pageshow', (event) => { if (event.persisted) resume(); });
  resume();
})();

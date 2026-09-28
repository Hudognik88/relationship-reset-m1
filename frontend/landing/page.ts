import { escapeHtml as e, interestLink, offer, questions } from './content';

function link(href: string, label: string, className = ''): string {
  return `<a href="${e(href)}" class="${e(className)}">${e(label)}</a>`;
}

function brand(): string {
  return '<a class="wordmark" href="#top" aria-label="После ссоры — в начало"><span class="brand-icon" aria-hidden="true">пс</span><span>после ссоры<span class="brand-caption">RELATIONSHIP RESET</span></span></a>';
}

function faq(): string {
  return questions.map(item => `<details class="question"><summary>${e(item.question)}</summary><p>${e(item.answer)}</p></details>`).join('\n');
}

export function renderPage(css: string): string {
  const price = `${offer.price} ₽`;
  return `<!doctype html>
<html lang="ru"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="description" content="Письменный разбор одной ссоры: понятный следующий шаг и до двух корректировок за 7 дней. Планируемый пилот — 990 ₽. Посмотрите вымышленный пример и узнайте условия.">
<meta name="theme-color" content="#f8f9f6">
<meta name="robots" content="noindex,follow">
<title>После ссоры — разобраться и выбрать следующий шаг</title>
<style>${css}</style></head>
<body class="pilot" id="top">
<a class="skip-link" href="#main">Перейти к содержанию</a>
<div class="opening-note">Готовим первый пилот. Сейчас можно узнать о старте — без оплаты.</div>
<header class="site-header shell">
  ${brand()}
  <nav class="site-nav" aria-label="Навигация по странице">
    ${link('#example', 'Пример разбора')}${link('#format', 'Что входит')}${link('#questions', 'Вопросы')}
  </nav>
  ${link('#join', 'Узнать о пилоте', 'header-cta')}
</header>
<main id="main">
  <section class="hero-section shell" aria-labelledby="hero-title">
    <div class="hero-copy">
      <p class="eyebrow">Письменный разбор одной ссоры · 18+</p>
      <h1 id="hero-title">Ссора уже случилась.<br><em>Что делать теперь?</em></h1>
      <p class="hero-intro">Поможем отделить факты от догадок и выбрать один спокойный следующий шаг. С учётом того, что вы уже пробовали и о чём попросил партнёр.</p>
      <p class="hero-price"><strong>${price}</strong><span>первый разбор + до ${offer.corrections} корректировок<br>в течение ${offer.days} дней с первого ответа</span></p>
      <div class="hero-actions">${link('#join', 'Узнать условия пилота', 'button button-primary')}${link('#example', 'Посмотреть пример', 'text-link')}</div>
      <p class="quiet">Цена для первых трёх покупателей. Сейчас — только интерес к запуску.</p>
    </div>
    <aside class="sample-stage" aria-label="Фрагмент вымышленного разбора">
      <div class="sample-topline"><span>Меньше догадок.</span><span>Один понятный шаг.</span></div>
      <article class="sample-paper">
        <p class="document-label">Фрагмент разбора <span>Вымышленный пример</span></p>
        <p class="sample-story">«Партнёр попросил паузу.<br>Я снова написал —<br>стало только хуже».</p>
        <div class="sample-rule"></div>
        <p class="small-label">Что известно</p>
        <p>Была просьба о паузе. Новое сообщение усилило напряжение. О чувствах партнёра мы пока не знаем.</p>
        <div class="sample-action"><p class="small-label">Ваш следующий шаг</p><h2>Сейчас — выдержать паузу.</h2><p>Не добавлять ещё одно сообщение. Подготовить заметку для себя, без отправки.</p></div>
        <div class="document-footer"><span>Факты → границы → действие</span><span>01</span></div>
      </article>
      <p class="sample-caption">Не универсальный совет. Шаг зависит от ситуации.</p>
    </aside>
  </section>
  <div class="benefit-line shell" aria-label="Особенности формата"><p><strong>Можно начать одному</strong><span>Участие партнёра не требуется</span></p><p><strong>Без обязательного созвона</strong><span>Ответ, к которому можно вернуться</span></p><p><strong>С проверкой человеком</strong><span>ИИ помогает подготовить черновик</span></p></div>
  <section class="section shell relevance" aria-labelledby="fit-title">
    <div class="section-heading"><p class="eyebrow">Когда хочется ясности</p><h2 id="fit-title">Не всегда понятно,<br>что поможет сейчас.</h2></div>
    <div class="situations"><article><span class="item-number">01</span><h3>Хочется написать ещё раз</h3><p>Но непонятно, станет ли от нового сообщения лучше.</p></article><article><span class="item-number">02</span><h3>Вы уже извинились</h3><p>А напряжение осталось. Повторять то же самое не хочется.</p></article><article><span class="item-number">03</span><h3>Ссоры идут по кругу</h3><p>Нужен небольшой шаг, который учитывает прошлые попытки.</p></article></div>
    <p class="fit-note">Формат для взрослых после обычного конфликта. При угрозах, насилии, принуждении или страхе за безопасность он не подходит.</p>
  </section>
  <section class="example-section" id="example" aria-labelledby="example-title"><div class="shell section">
    <div class="section-heading split-heading"><div><p class="eyebrow">Сначала посмотрите результат</p><h2 id="example-title">Не «просто поговорите».<br>Шаг с учётом ваших обстоятельств.</h2></div><p>Ниже — вымышленная ситуация и образец ответа. Это пример формата, а не история клиента или обещание примирения.</p></div>
    <div class="full-example"><aside class="case-story"><p class="small-label">Ситуация</p><blockquote>«После ссоры партнёр попросил оставить его в покое. Я хотел объясниться и отправил ещё одно сообщение. Он разозлился. Как теперь всё исправить?»</blockquote><p class="quiet">В примере нет насилия и угроз.</p></aside>
    <article class="review-document" aria-label="Полный вымышленный пример разбора"><div class="review-heading"><h3>Ваш разбор</h3><span>Пример</span></div>
      <div class="review-row"><span class="review-index">01</span><div><h4>Что можно сказать по фактам</h4><p>Партнёр прямо попросил паузу. Попытка объясниться усилила напряжение. Мы не знаем, что он чувствует и чего хочет дальше.</p></div></div>
      <div class="review-row highlighted"><span class="review-index">02</span><div><h4>Один шаг сейчас</h4><p>Не отправляйте новое сообщение. Для себя запишите, за какой конкретный поступок готовы отвечать и что можете изменить в своём поведении. Эта заметка пока остаётся у вас.</p></div></div>
      <div class="review-row"><span class="review-index">03</span><div><h4>Чего избегать</h4><p>Не обходите паузу звонками, подарками или сообщениями через знакомых. Прошедшее время само по себе не отменяет просьбу о пространстве.</p></div></div>
      <div class="review-row"><span class="review-index">04</span><div><h4>К чему вернуться в корректировке</h4><p>Удалось ли выдержать паузу? Возобновил ли партнёр контакт по своей инициативе? Появились ли новые факты или границы?</p></div></div>
    </article></div>
  </div></section>
  <section class="section shell" id="format" aria-labelledby="format-title">
    <div class="section-heading split-heading"><div><p class="eyebrow">Что будет в пилоте</p><h2 id="format-title">Одна ситуация.<br>Семь дней внимания к ней.</h2></div><p>Первый разбор и до двух возвращений с новыми фактами. Один и тот же объём услуги для каждого участника.</p></div>
    <ol class="process"><li><span class="item-number">01</span><div><h3>Расскажете, что произошло</h3><p>После запуска — короткая анкета о ситуации, ваших попытках и границах контакта. До оплаты уточним, подходит ли запрос для этого формата.</p></div><span class="process-detail">Без созвона</span></li><li><span class="item-number">02</span><div><h3>Получите первый разбор</h3><p>Факты, один следующий шаг и то, чего лучше избегать. ИИ поможет с черновиком; человек проверит ответ перед выдачей.</p></div><span class="process-detail">В течение 24 часов*</span></li><li><span class="item-number">03</span><div><h3>Вернётесь с тем, что изменилось</h3><p>В течение семи дней с первого ответа можно прислать до двух запросов на корректировку. Ответ на каждый — в течение 24 часов.</p></div><span class="process-detail">До двух корректировок</span></li></ol>
    <p class="quiet timing-note">* После получения заполненной анкеты и подтверждения оплаты. Запрос на корректировку в последний день также получит ответ в течение 24 часов.</p>
  </section>
  <section class="human-section"><div class="shell human-layout"><div><p class="eyebrow">Кто готовит ответ</p><h2>ИИ — для черновика.<br>Человек — для проверки.</h2><p>Перед отправкой человек сверяет ответ с вашей историей: не появились ли выдуманные факты, не повторяется ли неудачная попытка, учтены ли границы партнёра.</p><p class="human-limits">Это не психотерапия и не диагностика. Мы не определяем скрытые мотивы партнёра и не обещаем вернуть отношения.</p></div><aside class="responsibility"><p class="small-label">Кто отвечает за услугу</p><h3>${e(offer.seller)}</h3><p>Самозанятая. Отвечает за оказание услуги, сроки, поддержку и рассмотрение обращений о возврате.</p>${link(`mailto:${offer.email}`, offer.email, 'contact-link')}<p class="quiet">По общим вопросам о формате.<br>Личную историю пока присылать не нужно.</p></aside></div></section>
  <section class="section shell offer-layout" id="join" aria-labelledby="join-title"><div class="offer-copy"><p class="eyebrow">Первая группа · планируемый пилот</p><h2 id="join-title">Начните с ясности<br>о самом формате.</h2><p>Напишите, если вам интересен разбор за ${price}. Когда пилот будет готов, сообщим условия и порядок участия. Решение об оплате сможете принять после этого.</p><p class="contact-instruction">Сейчас достаточно: «Мне интересен пилот».<br>Без анкеты, личной истории и чужой переписки.</p></div><aside class="price-panel" aria-label="Цена и условия пилота"><p class="small-label">7-дневный разбор после ссоры</p><p class="offer-price">${price}<span>разовая оплата после запуска</span></p><ul class="included"><li>Первый письменный разбор</li><li>До ${offer.corrections} корректировок по новым фактам</li><li>${offer.days} дней с момента первого ответа</li><li>Проверка каждого ответа человеком</li></ul><p class="price-note">Для первых ${offer.firstGroup} покупателей. Без подписки, автопродления и безлимитного чата.</p>${link(interestLink(), 'Написать о пилоте', 'button button-primary')}<p class="mailto-note">Откроется ваша почта. Письмо — запрос информации, не заказ и не бронирование. Оплата сейчас не принимается.</p><p class="email-fallback">Если кнопка не открылась, напишите на<br>${link(`mailto:${offer.email}`, offer.email)}</p></aside></section>
  <section class="section shell faq-layout" id="questions" aria-labelledby="questions-title"><div class="section-heading"><p class="eyebrow">Перед решением</p><h2 id="questions-title">О чём ещё<br>важно знать</h2></div><div class="faq-list">${faq()}</div></section>
  <aside class="safety-note shell"><h2>Если сейчас небезопасно</h2><p>При угрозах, насилии или риске причинить вред себе в первую очередь нужна помощь по безопасности. При непосредственной опасности обратитесь в местные экстренные службы, когда это безопасно. Этот сервис не является кризисной службой.</p></aside>
</main>
<footer class="site-footer shell"><div class="footer-main">${brand()}<div>${link(offer.telegram, 'Канал проекта в Telegram')}${link(`mailto:${offer.email}`, offer.email)}</div></div><div class="footer-fine"><p>Самозанятая ${e(offer.seller)}<br>ИНН ${e(offer.taxId)}</p><p>Сейчас принимаем только письма об интересе.<br>Условия оплаты, возвратов и обработки анкет опубликуем до продаж.</p></div><p class="footer-privacy">На этой странице нет формы, аналитических счётчиков и передачи анкеты в ИИ. Отправленное вами письмо поступит на почту продавца; почтовый сервис обрабатывает его по своим правилам. Хостинг может вести технические журналы запросов.</p></footer>
</body></html>\n`;
}

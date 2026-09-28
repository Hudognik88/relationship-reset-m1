<?php
declare(strict_types=1);
require dirname(__DIR__) . '/backend/bootstrap.php';
require __DIR__ . '/backend-fixture.php';
$passed = 0;
function check(bool $condition, string $name): void
{
    global $passed;
    if (!$condition) {
        fwrite(STDERR, "FAIL: {$name}\n");
        exit(1);
    }
    $passed++;
}
function reject(callable $call, int $status, string $name): void
{
    try {
        $call();
        check(false, $name);
    } catch (RR\Problem $problem) {
        check($problem->status === $status, $name);
    }
}
function object_payload(array $payload): mixed
{
    return RR\Http::decode(RR\Validation::json($payload));
}
$token = rtrim(strtr(base64_encode(random_bytes(32)), '+/', '-_'), '=');
$hash = hash('sha256', $token);
check(RR\Http::authenticated(['HTTP_AUTHORIZATION' => 'Bearer ' . $token], $hash), 'valid bearer');
check(!RR\Http::authenticated([], $hash), 'missing bearer denied');
check(!RR\Http::authenticated(['HTTP_AUTHORIZATION' => 'Bearer ' . str_repeat('x', 43)], $hash), 'wrong bearer denied');
check(!RR\Http::authenticated(['HTTP_AUTHORIZATION' => 'Bearer ' . $token . "\r\n"], $hash), 'header controls denied');
check(!RR\Http::authenticated(['HTTP_AUTHORIZATION' => 'Basic ' . $token], $hash), 'wrong auth scheme denied');
$fixture = backend_fixture();
$clean = RR\Validation::casePayload(object_payload($fixture));
check($clean === $fixture, 'valid exact frontend schema accepted');
$changed = $fixture;
$changed['synthetic'] = false;
reject(fn() => RR\Validation::casePayload(object_payload($changed)), 422, 'real case flag denied');
$changed['synthetic'] = 'true';
reject(fn() => RR\Validation::casePayload(object_payload($changed)), 422, 'truthy string denied');
$changed = $fixture;
$changed['email'] = 'not-saved@example.invalid';
reject(fn() => RR\Validation::casePayload(object_payload($changed)), 422, 'extra contact field denied');
$changed = $fixture;
$changed['questionnaire']['age'] = 'yes';
reject(fn() => RR\Validation::casePayload(object_payload($changed)), 422, 'invalid enum denied');
$changed = $fixture;
unset($changed['questionnaire']['safety']);
reject(fn() => RR\Validation::casePayload(object_payload($changed)), 422, 'missing safety field denied');
$changed = $fixture;
$changed['questionnaire']['situation'] = '   ';
reject(fn() => RR\Validation::casePayload(object_payload($changed)), 422, 'empty situation denied');
check(RR\Validation::text(str_repeat('я', 1000), 1000, true) === str_repeat('я', 1000), 'Russian text length preserved');
reject(fn() => RR\Validation::text(str_repeat('🙂', 501), 1000, false), 422, 'emoji counts UTF16 units');
reject(fn() => RR\Validation::text("x\x00y", 1000, false), 422, 'null controls denied');
reject(fn() => RR\Validation::text("\xff", 1000, false), 422, 'invalid UTF8 denied');
reject(fn() => RR\Http::decode('{'), 400, 'malformed JSON denied');
reject(fn() => RR\Http::decode(str_repeat(' ', 16385)), 413, 'body size bounded');
reject(fn() => RR\Validation::casePayload(RR\Http::decode('[]')), 422, 'array instead of object denied');
$draft = ['synthetic' => true, 'client_request_id' => backend_uuid(), 'text' => 'Вымышленный черновик.'];
check(RR\Validation::draftPayload(object_payload($draft)) === $draft, 'draft accepted');
$draft['reviewed'] = true;
reject(fn() => RR\Validation::draftPayload(object_payload($draft)), 422, 'review flag cannot be promoted');
reject(fn() => RR\Validation::requestId('../../secret'), 422, 'request identifier injection denied');
fwrite(STDOUT, "Backend unit checks passed: {$passed}.\n");

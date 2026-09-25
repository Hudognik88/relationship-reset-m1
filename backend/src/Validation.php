<?php
declare(strict_types=1);
namespace RR;

final class Problem extends \RuntimeException
{
    public function __construct(public readonly int $status, public readonly string $error)
    {
        parent::__construct($error);
    }
}

final class Validation
{
    // These exact enum values match app.js. This API does not alter the local form.
    public const ENUMS = [
        'age' => ['adult', 'minor'],
        'stage' => ['under6m', '6to12m', '1to3y', '3to7y', '7plus'],
        'safety' => ['no', 'yes_unsure'],
        'boundary' => ['clear', 'space', 'no_contact', 'unsure'],
        'timing' => ['today', 'yesterday', '2to3days', 'older'],
        'partner' => ['talk', 'quiet', 'angry', 'space', 'distant', 'left', 'other'],
        'user' => ['talk', 'apology', 'defend', 'space', 'messages', 'withdraw', 'help', 'other'],
        'recurrence' => ['first', 'sometimes', 'often', 'always'],
        'helpful' => ['pause', 'listen', 'practical', 'none', 'unsure'],
        'failureType' => ['messages', 'apology', 'talk', 'pause', 'other', 'none', 'unsure'],
        'goal' => ['talk', 'apology', 'calm', 'space', 'understand', 'pattern', 'other'],
    ];

    public static function object(mixed $value, array $keys): array
    {
        if (!$value instanceof \stdClass) {
            throw new Problem(422, 'invalid_payload');
        }
        $object = get_object_vars($value);
        $actual = array_keys($object);
        sort($actual);
        sort($keys);
        if ($actual !== $keys) {
            throw new Problem(422, 'invalid_fields');
        }
        return $object;
    }

    public static function requestId(mixed $id): string
    {
        if (!is_string($id) || !preg_match('/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/D', $id)) {
            throw new Problem(422, 'invalid_client_request_id');
        }
        return $id;
    }

    public static function text(mixed $value, int $max, bool $required): string
    {
        if (!is_string($value) || preg_match('//u', $value) !== 1
            || preg_match('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/', $value)) {
            throw new Problem(422, 'invalid_text');
        }
        $value = trim($value);
        // Match HTML maxlength's UTF-16 code units, including emoji as two units.
        $chars = preg_split('//u', $value, -1, PREG_SPLIT_NO_EMPTY);
        $length = 0;
        foreach ($chars as $char) {
            $length += strlen($char) === 4 ? 2 : 1;
        }
        if ($length > $max || ($required && $length === 0)) {
            throw new Problem(422, 'invalid_text_length');
        }
        return $value;
    }

    public static function casePayload(mixed $body): array
    {
        $body = self::object($body, ['schema_version', 'synthetic', 'client_request_id', 'questionnaire']);
        if ($body['schema_version'] !== 'm1-cis-v1' || $body['synthetic'] !== true) {
            throw new Problem(422, 'synthetic_case_required');
        }
        $requestId = self::requestId($body['client_request_id']);
        $data = self::object($body['questionnaire'], array_merge(array_keys(self::ENUMS), ['situation', 'success', 'failure']));
        $canonical = [];
        foreach (self::ENUMS as $key => $allowed) {
            if (!is_string($data[$key]) || !in_array($data[$key], $allowed, true)) {
                throw new Problem(422, 'invalid_questionnaire_enum');
            }
            $canonical[$key] = $data[$key];
        }
        $canonical['situation'] = self::text($data['situation'], 2000, true);
        $canonical['success'] = self::text($data['success'], 1000, false);
        $canonical['failure'] = self::text($data['failure'], 1000, false);
        return [
            'schema_version' => 'm1-cis-v1',
            'synthetic' => true,
            'client_request_id' => $requestId,
            'questionnaire' => $canonical,
        ];
    }

    public static function draftPayload(mixed $body): array
    {
        $body = self::object($body, ['synthetic', 'client_request_id', 'text']);
        if ($body['synthetic'] !== true) {
            throw new Problem(422, 'synthetic_case_required');
        }
        return [
            'synthetic' => true,
            'client_request_id' => self::requestId($body['client_request_id']),
            'text' => self::text($body['text'], 4000, true),
        ];
    }

    public static function json(array $value): string
    {
        return json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    }
}

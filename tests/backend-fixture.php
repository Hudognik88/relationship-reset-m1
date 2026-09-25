<?php
declare(strict_types=1);
function backend_uuid(): string
{
    $bytes = random_bytes(16);
    $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
    $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
    $hex = bin2hex($bytes);
    return substr($hex, 0, 8) . '-' . substr($hex, 8, 4) . '-' . substr($hex, 12, 4) . '-' . substr($hex, 16, 4) . '-' . substr($hex, 20);
}
function backend_fixture(): array
{
    return [
        'schema_version' => 'm1-cis-v1', 'synthetic' => true,
        'client_request_id' => backend_uuid(),
        'questionnaire' => [
            'age' => 'adult', 'stage' => '1to3y', 'safety' => 'no', 'boundary' => 'space',
            'timing' => 'yesterday', 'partner' => 'space', 'user' => 'messages',
            'recurrence' => 'sometimes', 'helpful' => 'listen', 'failureType' => 'messages',
            'goal' => 'understand',
            'situation' => 'Вымышленная репетиция: персонажи поспорили о бытовых делах. Один попросил паузу.',
            'success' => 'Вымышленный пример: раньше помогало выслушать без спора.',
            'failure' => 'Вымышленный пример: повторное объяснение усилило напряжение.',
        ],
    ];
}

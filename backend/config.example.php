<?php
declare(strict_types=1);

// Copy to relationship-reset-private/shared/config.php OUTSIDE public_html.
// Never commit an actual config or token. This example intentionally fails closed.
return [
    'environment' => 'staging', // Only staging is supported in this first backend.
    'operator_token_sha256' => '', // SHA-256 of a random 32-byte bearer token.
    'database' => [
        'host' => 'localhost',
        'port' => 3306,
        'name' => '',
        'user' => '',
        'password' => '',
    ],
];

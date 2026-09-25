<?php
declare(strict_types=1);
namespace RR;

final class Store
{
    public function __construct(private readonly \PDO $pdo) {}

    public function ready(): bool
    {
        $checksum = hash_file('sha256', dirname(__DIR__) . '/migrations/001_initial.sql');
        $query = $this->pdo->prepare('SELECT checksum FROM rr_schema_migrations WHERE version = ?');
        $query->execute(['001_initial']);
        $row = $query->fetch();
        if (!$row || !hash_equals($checksum, $row['checksum'])) {
            return false;
        }
        $this->pdo->query('SELECT id, questionnaire FROM rr_cases LIMIT 0');
        $this->pdo->query('SELECT id, reviewed FROM rr_drafts LIMIT 0');
        return true;
    }

    public function createCase(array $payload): array
    {
        $hash = hash('sha256', Validation::json($payload));
        $id = bin2hex(random_bytes(16));
        try {
            $query = $this->pdo->prepare(
                'INSERT INTO rr_cases (id, client_request_id, request_hash, schema_version, synthetic, questionnaire) VALUES (?, ?, ?, ?, 1, ?)'
            );
            $query->execute([$id, $payload['client_request_id'], $hash, 'm1-cis-v1', Validation::json($payload['questionnaire'])]);
            return [201, $this->getCase($id)];
        } catch (\PDOException $error) {
            if (($error->errorInfo[1] ?? null) !== 1062) {
                throw $error;
            }
            // The unique index arbitrates simultaneous retries, not a racy pre-insert check.
            $query = $this->pdo->prepare('SELECT id, request_hash FROM rr_cases WHERE client_request_id = ?');
            $query->execute([$payload['client_request_id']]);
            $row = $query->fetch();
            if (!$row) {
                throw $error;
            }
            if (!hash_equals($row['request_hash'], $hash)) {
                throw new Problem(409, 'idempotency_conflict');
            }
            return [200, $this->getCase($row['id'])];
        }
    }

    public function getCase(string $id): array
    {
        $query = $this->pdo->prepare('SELECT id, schema_version, synthetic, questionnaire, created_at FROM rr_cases WHERE id = ?');
        $query->execute([$id]);
        $row = $query->fetch();
        if (!$row) {
            throw new Problem(404, 'case_not_found');
        }
        return [
            'id' => $row['id'],
            'schema_version' => $row['schema_version'],
            'synthetic' => (bool) $row['synthetic'],
            'questionnaire' => json_decode($row['questionnaire'], true, 32, JSON_THROW_ON_ERROR),
            'status' => 'stored_for_rehearsal',
            'created_at' => str_replace(' ', 'T', $row['created_at']) . 'Z',
        ];
    }

    public function createDraft(string $caseId, array $payload): array
    {
        $case = $this->getCase($caseId);
        if ($case['synthetic'] !== true) {
            throw new Problem(409, 'synthetic_case_required');
        }
        $hash = hash('sha256', Validation::json(['case_id' => $caseId, 'payload' => $payload]));
        $id = bin2hex(random_bytes(16));
        try {
            $query = $this->pdo->prepare(
                'INSERT INTO rr_drafts (id, case_id, client_request_id, request_hash, draft_text, reviewed) VALUES (?, ?, ?, ?, ?, 0)'
            );
            $query->execute([$id, $caseId, $payload['client_request_id'], $hash, $payload['text']]);
            return [201, $this->draftResult($id, $caseId)];
        } catch (\PDOException $error) {
            if (($error->errorInfo[1] ?? null) !== 1062) {
                throw $error;
            }
            $query = $this->pdo->prepare('SELECT id, request_hash FROM rr_drafts WHERE client_request_id = ?');
            $query->execute([$payload['client_request_id']]);
            $row = $query->fetch();
            if (!$row) {
                throw $error;
            }
            if (!hash_equals($row['request_hash'], $hash)) {
                throw new Problem(409, 'idempotency_conflict');
            }
            return [200, $this->draftResult($row['id'], $caseId)];
        }
    }

    private function draftResult(string $id, string $caseId): array
    {
        return ['id' => $id, 'case_id' => $caseId, 'synthetic' => true, 'reviewed' => false, 'status' => 'draft_requires_human_review'];
    }
}

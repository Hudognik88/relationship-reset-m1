/** Shared compile-time contracts. This file emits no browser code. */
interface RRIntake {
  age: string;
  stage: string;
  situation: string;
  safety: string;
  boundary: string;
  timing: string;
  partner: string;
  user: string;
  recurrence: string;
  helpful: string;
  success: string;
  failureType: string;
  failure: string;
  goal: string;
}

type RRField = keyof RRIntake;
type RRChoiceField = Exclude<RRField, 'situation' | 'success' | 'failure'>;
type RRChoice = readonly [value: string, label: string];

interface RRStandardMove {
  mode: 'STANDARD';
  kind: 'no_contact' | 'space' | 'private' | 'listen';
  title: string;
  action: string;
  avoid: string;
  why: string;
  words?: string;
}

type RRMove = RRStandardMove | { mode: 'SAFETY' } | { mode: 'MINOR' } | { mode: 'CLARIFY' };

interface RRResetLogicApi {
  safety(data: Partial<RRIntake>): boolean;
  move(data: Partial<RRIntake>): RRMove;
}

interface RRStoredCase {
  schemaVersion: 'm1-cis-v1';
  case_id: string;
  mode: 'STANDARD';
  ts: string;
  data: RRIntake;
}

/** Available after reset-logic.js in a browser; legacy Node tests use exports. */
declare var ResetLogic: RRResetLogicApi;
declare const module: { exports: RRResetLogicApi } | undefined;

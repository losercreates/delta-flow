import { CircuitBreakerStatusDto } from './dashboard';

export type ToxicType = 'LATENCY' | 'PACKET_LOSS' | 'PARTITION' | 'SLICER' | 'BANDWIDTH' | 'TIMEOUT';

export interface ToxicDetailDto {
  name: string;
  type: string;
  stream: string;
  toxicity: number;
  attributes: Record<string, unknown>;
}

export interface ProxyStatusDto {
  name: string;
  listenAddress: string;
  upstreamAddress: string;
  enabled: boolean;
  activeToxics: ToxicDetailDto[];
}

export interface ChaosStatusDto {
  toxiproxyEnabled: boolean;
  activeToxicsCount: number;
  proxies: ProxyStatusDto[];
  circuitBreakers: CircuitBreakerStatusDto[];
}

export interface ChaosInjectionRequest {
  proxyName: string;
  toxicType: ToxicType | string;
  latencyMs?: number;
  jitterMs?: number;
  dropRate?: number;
  operatorId: string;
  justification: string;
}

export interface CircuitBreakerTransitionRequest {
  targetState: 'OPEN' | 'CLOSED' | 'HALF_OPEN';
  operatorId: string;
  justification: string;
}

export interface InvariantViolationDto {
  violationType: 'DOUBLE_SPEND' | 'ORPHANED_DEBIT' | 'BALANCE_MISMATCH' | 'DISCREPANCY' | string;
  sagaId: string | null;
  accountId: string | null;
  details: string;
  amount: number | null;
  detectedAt: string;
}

export interface InvariantVerificationReport {
  verificationId?: string;
  timestamp: string;
  tenantId: string | null;
  totalTransactionsAudited: number;
  successfulCount: number;
  compensatedCount: number;
  failedCount: number;
  totalDebits: number;
  totalCredits: number;
  totalRefunds: number;
  doubleSpendCount: number;
  orphanedDebitCount: number;
  violations: InvariantViolationDto[];
  passed: boolean;
}

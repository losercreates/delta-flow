export interface ClusterNodeDto {
  nodeId: string;
  hostname: string;
  port: number;
  role: 'LEADER' | 'STANDBY';
  startedAt: string;
  lastHeartbeatAt: string;
  isHealthy: boolean;
  version: string;
}

export interface LeaderStatusDto {
  leaderNodeId: string;
  isCurrentNodeLeader: boolean;
  leaseAcquiredAt: string;
  leaseExpiresAt: string;
  leaseDurationMs: number;
}

export interface RetentionSummaryDto {
  enabled: boolean;
  retentionDays: number;
  batchSize: number;
  lastRunAt: string | null;
  totalPrunedRecords: number;
  oldestRemainingResolvedRecord: string | null;
}

export interface RetentionTriggerRequest {
  retentionDays?: number;
  batchSize?: number;
  operatorId: string;
  justification: string;
}

export interface OutboxLeaseReclaimResult {
  scannedCount: number;
  reclaimedCount: number;
  deadLetteredCount: number;
  timestamp: string;
}

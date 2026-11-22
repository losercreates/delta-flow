import {
  ClusterNodeDto,
  LeaderStatusDto,
  RetentionSummaryDto,
  RetentionTriggerRequest,
  OutboxLeaseReclaimResult,
} from '../types/cluster';

const BASE_URL = '/api/v1/cluster';

export async function fetchClusterNodes(): Promise<ClusterNodeDto[]> {
  const response = await fetch(`${BASE_URL}/nodes`);
  if (!response.ok) {
    throw new Error(`Failed to fetch cluster nodes: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function fetchLeaderStatus(): Promise<LeaderStatusDto> {
  const response = await fetch(`${BASE_URL}/leader`);
  if (!response.ok) {
    throw new Error(`Failed to fetch leader status: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function fetchRetentionStatus(): Promise<RetentionSummaryDto> {
  const response = await fetch(`${BASE_URL}/retention/status`);
  if (!response.ok) {
    throw new Error(`Failed to fetch retention status: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function triggerRetentionPruning(
  request: RetentionTriggerRequest
): Promise<RetentionSummaryDto> {
  const response = await fetch(`${BASE_URL}/retention/trigger`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    throw new Error(`Failed to trigger retention pruning: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function triggerOutboxLeaseReclaim(): Promise<OutboxLeaseReclaimResult> {
  const response = await fetch(`${BASE_URL}/outbox/reclaim-leases`, {
    method: 'POST',
  });
  if (!response.ok) {
    throw new Error(`Failed to trigger outbox lease reclaim: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

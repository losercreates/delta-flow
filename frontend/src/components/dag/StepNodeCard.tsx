import React from 'react';
import { Handle, Position } from '@xyflow/react';
import {
  CheckCircle2,
  Clock,
  AlertCircle,
  RotateCcw,
  Shield,
  Layers,
  Activity,
} from 'lucide-react';
import { SagaNodeDto, StepStatus } from '../../types/dashboard';

export interface StepNodeData {
  node: SagaNodeDto;
  isSelected?: boolean;
}

const statusStyles: Record<
  StepStatus,
  {
    border: string;
    bg: string;
    text: string;
    badgeBg: string;
    badgeText: string;
    glow: string;
  }
> = {
  COMPLETED: {
    border: 'border-emerald-500/80',
    bg: 'bg-emerald-950/30',
    text: 'text-emerald-300',
    badgeBg: 'bg-emerald-500/20',
    badgeText: 'text-emerald-300',
    glow: 'shadow-emerald-900/30',
  },
  EXECUTING: {
    border: 'border-sky-500',
    bg: 'bg-sky-950/40',
    text: 'text-sky-300',
    badgeBg: 'bg-sky-500/20',
    badgeText: 'text-sky-300',
    glow: 'shadow-sky-500/40 animate-pulse',
  },
  COMPENSATING: {
    border: 'border-amber-500',
    bg: 'bg-amber-950/40',
    text: 'text-amber-300',
    badgeBg: 'bg-amber-500/20',
    badgeText: 'text-amber-300',
    glow: 'shadow-amber-500/40 animate-pulse',
  },
  COMPENSATED: {
    border: 'border-amber-600/70',
    bg: 'bg-amber-950/20',
    text: 'text-amber-400',
    badgeBg: 'bg-amber-500/20',
    badgeText: 'text-amber-400',
    glow: 'shadow-amber-900/30',
  },
  FAILED: {
    border: 'border-rose-500',
    bg: 'bg-rose-950/40',
    text: 'text-rose-300',
    badgeBg: 'bg-rose-500/20',
    badgeText: 'text-rose-300',
    glow: 'shadow-rose-900/40',
  },
  SUSPENDED: {
    border: 'border-amber-400 border-dashed',
    bg: 'bg-amber-950/40',
    text: 'text-amber-300',
    badgeBg: 'bg-amber-500/20',
    badgeText: 'text-amber-300',
    glow: 'shadow-amber-500/30 animate-pulse',
  },
  TIMED_OUT: {
    border: 'border-rose-400',
    bg: 'bg-rose-950/40',
    text: 'text-rose-300',
    badgeBg: 'bg-rose-500/20',
    badgeText: 'text-rose-300',
    glow: 'shadow-rose-900/40',
  },
  NOT_STARTED: {
    border: 'border-slate-700',
    bg: 'bg-slate-900/50',
    text: 'text-slate-400',
    badgeBg: 'bg-slate-800',
    badgeText: 'text-slate-400',
    glow: 'shadow-transparent',
  },
};

function StatusIcon({ status }: { status: StepStatus }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle2 className="w-4 h-4 text-emerald-400" />;
    case 'EXECUTING':
      return <Activity className="w-4 h-4 text-sky-400 animate-spin" />;
    case 'COMPENSATING':
      return <RotateCcw className="w-4 h-4 text-amber-400 animate-spin" />;
    case 'COMPENSATED':
      return <RotateCcw className="w-4 h-4 text-amber-400" />;
    case 'SUSPENDED':
      return <Clock className="w-4 h-4 text-amber-400 animate-pulse" />;
    case 'TIMED_OUT':
      return <AlertCircle className="w-4 h-4 text-rose-400" />;
    case 'FAILED':
      return <AlertCircle className="w-4 h-4 text-rose-400" />;
    default:
      return <Clock className="w-4 h-4 text-slate-500" />;
  }
}

export const StepNodeCard: React.FC<{ data: StepNodeData }> = ({ data }) => {
  const { node, isSelected } = data;
  const style = statusStyles[node.status] || statusStyles.NOT_STARTED;

  // Calculate duration if timestamps exist
  let durationStr = '';
  if (node.startedAt && node.completedAt) {
    const ms = new Date(node.completedAt).getTime() - new Date(node.startedAt).getTime();
    durationStr = ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms}ms`;
  }

  return (
    <div
      className={`min-w-[220px] max-w-[260px] rounded-xl border-2 p-3 transition-all duration-200 backdrop-blur-md shadow-lg ${
        style.border
      } ${style.bg} ${style.glow} ${
        isSelected ? 'ring-2 ring-primary-500 shadow-xl scale-105' : 'hover:scale-[1.02]'
      }`}
    >
      <Handle
        type="target"
        position={Position.Left}
        className="!w-3 !h-3 !bg-slate-600 !border-2 !border-slate-300"
      />

      <div className="flex items-center justify-between gap-2 mb-2">
        <div className="flex items-center gap-1.5">
          <span className="text-[11px] font-mono px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 font-semibold">
            #{node.stepIndex}
          </span>
          {node.isParallel && (
            <span className="flex items-center gap-0.5 text-[10px] px-1.5 py-0.5 rounded bg-purple-900/40 text-purple-300 border border-purple-700/50">
              <Layers className="w-3 h-3" /> Branch
            </span>
          )}
        </div>

        <span
          className={`flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-full ${style.badgeBg} ${style.badgeText}`}
        >
          <StatusIcon status={node.status} />
          {node.status}
        </span>
      </div>

      <div className="font-semibold text-slate-100 text-sm truncate mb-2" title={node.stepName}>
        {node.stepName}
      </div>

      <div className="flex items-center justify-between text-[11px] text-slate-400 border-t border-slate-800/80 pt-2 mt-1">
        <div className="flex items-center gap-1 font-mono">
          <Shield className="w-3 h-3 text-slate-500" />
          <span>Tk #{node.fencingToken ?? 1}</span>
        </div>
        {durationStr && (
          <div className="flex items-center gap-1 font-mono text-slate-400">
            <Clock className="w-3 h-3" />
            <span>{durationStr}</span>
          </div>
        )}
      </div>

      <Handle
        type="source"
        position={Position.Right}
        className="!w-3 !h-3 !bg-slate-600 !border-2 !border-slate-300"
      />
    </div>
  );
};

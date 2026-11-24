import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  Node,
  Edge,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import { RefreshCw, Wrench, Layers, CheckCircle2, Lock } from 'lucide-react';
import { OperatorInterventionResult, SagaGraphDto, SagaNodeDto } from '../../types/dashboard';
import { StepNodeCard } from './StepNodeCard';
import { StepDetailDrawer } from './StepDetailDrawer';
import { InterventionModal } from '../intervention/InterventionModal';
import { useAuth } from '../../context/AuthContext';

interface SagaFlowDagProps {
  graph: SagaGraphDto | null;
  onRefresh?: () => void;
  onInterveneSuccess?: (result: OperatorInterventionResult) => void;
}

const NODE_WIDTH = 240;
const NODE_HEIGHT = 120;

function getLayoutedElements(nodes: Node[], edges: Edge[]) {
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));
  dagreGraph.setGraph({ rankdir: 'LR', nodesep: 60, ranksep: 100 });

  nodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const layoutedNodes = nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    return {
      ...node,
      position: {
        x: nodeWithPosition.x - NODE_WIDTH / 2,
        y: nodeWithPosition.y - NODE_HEIGHT / 2,
      },
    };
  });

  return { nodes: layoutedNodes, edges };
}

export const SagaFlowDag: React.FC<SagaFlowDagProps> = ({ graph, onRefresh, onInterveneSuccess }) => {
  const { hasRole } = useAuth();
  const canIntervene = hasRole('ROLE_OPERATOR') || hasRole('ROLE_ADMIN');

  const [selectedNode, setSelectedNode] = useState<SagaNodeDto | null>(null);
  const [isInterventionModalOpen, setIsInterventionModalOpen] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const nodeTypes = useMemo(() => ({ stepNode: StepNodeCard }), []);

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);

  const syncGraph = useCallback(() => {
    if (!graph || !graph.nodes || graph.nodes.length === 0) {
      setNodes([]);
      setEdges([]);
      return;
    }

    const rawNodes: Node[] = graph.nodes.map((n) => ({
      id: n.id,
      type: 'stepNode',
      data: {
        node: n,
        isSelected: selectedNode?.id === n.id,
      },
      position: { x: 0, y: 0 },
    }));

    const rawEdges: Edge[] = graph.edges.map((e) => {
      const isComp = e.type === 'COMPENSATION';
      return {
        id: e.id,
        source: e.source,
        target: e.target,
        animated: e.animated,
        type: 'smoothstep',
        style: {
          stroke: isComp ? '#f59e0b' : '#38bdf8',
          strokeWidth: 2,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: isComp ? '#f59e0b' : '#38bdf8',
        },
      };
    });

    const layouted = getLayoutedElements(rawNodes, rawEdges);
    setNodes(layouted.nodes);
    setEdges(layouted.edges);
  }, [graph, selectedNode, setNodes, setEdges]);

  useEffect(() => {
    syncGraph();
  }, [syncGraph]);

  const onNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      const sagaNode = graph?.nodes.find((n) => n.id === node.id) || null;
      setSelectedNode(sagaNode);
    },
    [graph]
  );

  if (!graph) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-slate-500 p-8">
        <Layers className="w-12 h-12 mb-3 text-slate-600" />
        <p className="text-base font-medium">Select a saga instance to inspect its DAG pipeline</p>
      </div>
    );
  }

  const isHaltedOrFailed = graph.status === 'FAILED' || graph.status === 'COMPENSATING';

  return (
    <div className="relative w-full h-full bg-slate-950 flex flex-col">
      {/* Top action toolbar */}
      <div className="absolute top-4 left-4 z-10 flex items-center gap-3 bg-slate-900/90 backdrop-blur-md px-4 py-2.5 rounded-xl border border-slate-800 shadow-xl">
        <div className="flex items-center gap-2">
          <span className="text-xs font-mono text-slate-400">Saga:</span>
          <span className="text-xs font-bold font-mono text-slate-100">{graph.sagaId.substring(0, 8)}...</span>
          <span
            className={`text-[10px] px-2 py-0.5 rounded-full font-bold ${
              graph.status === 'COMPLETED'
                ? 'bg-emerald-500/20 text-emerald-300'
                : graph.status === 'FAILED'
                ? 'bg-rose-500/20 text-rose-300'
                : graph.status === 'EXECUTING'
                ? 'bg-sky-500/20 text-sky-300 animate-pulse'
                : 'bg-amber-500/20 text-amber-300'
            }`}
          >
            {graph.status}
          </span>
        </div>

        <div className="h-4 w-px bg-slate-800 mx-1" />

        {onRefresh && (
          <button
            onClick={onRefresh}
            className="flex items-center gap-1.5 text-xs text-slate-300 hover:text-white px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 transition-colors"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            Refresh
          </button>
        )}

        <button
          onClick={() => setIsInterventionModalOpen(true)}
          disabled={!canIntervene}
          title={!canIntervene ? 'Requires ROLE_OPERATOR or ROLE_ADMIN privilege' : undefined}
          className={`flex items-center gap-1.5 text-xs px-3 py-1 rounded font-semibold transition-all shadow-md ${
            !canIntervene
              ? 'bg-slate-800 text-slate-500 cursor-not-allowed opacity-60'
              : isHaltedOrFailed
              ? 'bg-rose-600 hover:bg-rose-500 text-white ring-2 ring-rose-400 animate-bounce'
              : 'bg-amber-600/90 hover:bg-amber-500 text-white'
          }`}
        >
          {!canIntervene ? <Lock className="w-3.5 h-3.5" /> : <Wrench className="w-3.5 h-3.5" />}
          {!canIntervene ? 'Intervention Locked' : 'Manual Intervention'}
        </button>
      </div>

      {/* Confirmation Toast */}
      {toastMessage && (
        <div className="absolute top-16 left-4 z-20 flex items-center gap-2 bg-emerald-950/90 border border-emerald-500/50 text-emerald-200 text-xs px-4 py-2.5 rounded-xl shadow-2xl backdrop-blur-md animate-in fade-in">
          <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
          <span>{toastMessage}</span>
        </div>
      )}

      {/* Main Flow Canvas */}
      <div className="flex-1 w-full h-full">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={onNodeClick}
          nodeTypes={nodeTypes}
          fitView
          minZoom={0.2}
          maxZoom={1.5}
        >
          <Background color="#1e293b" gap={16} />
          <Controls className="!bg-slate-900 !border-slate-800 !fill-slate-300" />
          <MiniMap
            className="!bg-slate-900/90 !border-slate-800"
            nodeColor={(n) => {
              const status = (n.data as { node?: SagaNodeDto })?.node?.status;
              if (status === 'COMPLETED') return '#10b981';
              if (status === 'EXECUTING') return '#0284c7';
              if (status === 'FAILED') return '#f43f5e';
              if (status === 'COMPENSATED' || status === 'COMPENSATING') return '#f59e0b';
              return '#475569';
            }}
          />
        </ReactFlow>
      </div>

      {/* Slide-out detail drawer */}
      <StepDetailDrawer node={selectedNode} onClose={() => setSelectedNode(null)} />

      {/* Manual Intervention Modal */}
      <InterventionModal
        isOpen={isInterventionModalOpen}
        sagaId={graph.sagaId}
        onClose={() => setIsInterventionModalOpen(false)}
        onSuccess={(result) => {
          setToastMessage(`Intervention ${result.actionTaken} applied! Saga transitioned to ${result.newStatus}.`);
          setTimeout(() => setToastMessage(null), 5000);
          if (onInterveneSuccess) {
            onInterveneSuccess(result);
          }
          if (onRefresh) {
            onRefresh();
          }
        }}
      />
    </div>
  );
};

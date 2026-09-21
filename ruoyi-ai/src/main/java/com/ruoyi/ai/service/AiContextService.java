package com.ruoyi.ai.service;
import java.util.*; import org.springframework.stereotype.Service; import com.ruoyi.ai.domain.*; import com.ruoyi.ai.mapper.*; import com.ruoyi.ai.runtime.*; import com.ruoyi.common.exception.ServiceException; import com.ruoyi.common.utils.StringUtils;
@Service
public class AiContextService {
 private final AiMessageMapper messages; private final AiCheckpointMapper checkpoints; private final AiPromptService prompts; private final AgentRuntime runtime; private final AiRunService runs;
 public AiContextService(AiMessageMapper m,AiCheckpointMapper c,AiPromptService p,AgentRuntime runtime,AiRunService r){messages=m;checkpoints=c;prompts=p;this.runtime=runtime;runs=r;}
 public AiCheckpoint maybeCompact(AiConversation conversation,AiRun run,AiModel model,String reasoningEffort,String runtimeOverhead) {
  AiCheckpoint latest=checkpoints.selectLatest(conversation.getConversationId());
  if(!"0".equals(model.getAutoCompaction()))return latest;
  int covered=latest==null?0:latest.getCoveredSequenceNo(); List<AiMessage> recent=messages.selectAfter(conversation.getConversationId(),covered);
  AiPrompt system=prompts.require(AiPromptService.SYSTEM); int estimate=estimate(system.getContent(),latest==null?null:latest.getSummary(),recent,runtimeOverhead);
  int window=model.getContextWindowTokens()==null?65536:model.getContextWindowTokens();int threshold=model.getCompactionThresholdPercent()==null?75:model.getCompactionThresholdPercent();
  if(estimate < (long)window*threshold/100)return latest;
  int latestUser=messages.latestUserSequence(conversation.getConversationId());int cutoff=latestUser-1;if(cutoff<=covered)return latest;
  List<AiMessage> segment=messages.selectRange(conversation.getConversationId(),covered,cutoff);if(segment.isEmpty())return latest;
  if(!runs.beginCompaction(run.getRunId()))return latest;
  AiPrompt cpPrompt=prompts.require(AiPromptService.COMPACTION);StringBuilder payload=new StringBuilder();
  if(latest!=null&&StringUtils.isNotBlank(latest.getSummary()))payload.append("已有 Checkpoint：\n").append(latest.getSummary()).append("\n\n");
  payload.append("需要压缩的后续历史：\n");appendCompactionHistory(payload,segment);
  try{
   AgentRuntimeRequest request=new AgentRuntimeRequest(model.getModelId(),reasoningEffort,"ruoyi:compaction:"+conversation.getConversationId(),
    List.of(AgentRuntimeMessage.system(prompts.render(cpPrompt,Map.of("conversationId",conversation.getConversationId(),"modelCode",StringUtils.defaultString(model.getModelCode())))),AgentRuntimeMessage.user(payload.toString())),List.of(),run.getRunId(),true);
   AgentRuntimeResult response=runs.call(run.getRunId(),()->runtime.call(request));
   runs.recordUsage(run.getRunId(),response.usage());
   if(response==null||StringUtils.isBlank(response.text()))throw new ServiceException("上下文压缩未返回有效 Checkpoint");
   if(!runs.endCompaction(run.getRunId()))return latest;
   AiCheckpoint cp=new AiCheckpoint();cp.setConversationId(conversation.getConversationId());cp.setRunId(run.getRunId());cp.setCoveredSequenceNo(cutoff);cp.setSummary(response.text().trim());cp.setModelId(model.getModelId());cp.setModelCode(model.getModelCode());cp.setEstimatedTokens(estimate);cp.setStatus("ACTIVE");checkpoints.insert(cp);return cp;
  }catch(InterruptedException e){Thread.interrupted();return latest;}catch(ServiceException e){runs.endCompaction(run.getRunId());throw e;}catch(Exception e){runs.endCompaction(run.getRunId());throw new ServiceException("上下文压缩失败："+safe(e));}
 }
 private void appendCompactionHistory(StringBuilder payload,List<AiMessage> segment){
  Map<Long,AiRun> runStates=new HashMap<>();
  for(int i=0;i<segment.size();i++){
   AiMessage m=segment.get(i);
   AiMessage previous=i>0?segment.get(i-1):null;
   AiMessage next=i+1<segment.size()?segment.get(i+1):null;
   appendRunState(payload,m,runStates);
   if("ASSISTANT".equals(m.getRole())&&StringUtils.isNotBlank(m.getToolCallId())){
    boolean paired=next!=null&&"TOOL".equals(next.getRole())&&Objects.equals(m.getToolCallId(),next.getToolCallId());
    if(!paired){
     payload.append(m.getSequenceNo()).append(" ASSISTANT: [已取消或未完成的页面工具调用，不得视为已执行成功] ");
     appendToolDetails(payload,m);payload.append("\n");continue;
    }
   }
   if("TOOL".equals(m.getRole())){
    boolean paired=previous!=null&&"ASSISTANT".equals(previous.getRole())&&Objects.equals(previous.getToolCallId(),m.getToolCallId());
    if(!paired)payload.append("[以下 Tool Result 缺少配对 Tool Call，需要复核，不得据此重复 WRITE] ");
   }
   payload.append(m.getSequenceNo()).append(" ").append(m.getRole()).append(": ").append(StringUtils.defaultString(m.getContent()));
   appendToolDetails(payload,m);payload.append("\n");
  }
 }
 private void appendRunState(StringBuilder payload,AiMessage message,Map<Long,AiRun> cache){
  if(message.getRunId()==null)return;AiRun state=cache.get(message.getRunId());
  if(state==null){state=runs.get(message.getRunId());if(state!=null)cache.put(message.getRunId(),state);}
  if(state==null)return;
  payload.append("[run=").append(state.getRunId()).append(" status=").append(state.getStatus());
  if(StringUtils.isNotBlank(state.getCancelReason()))payload.append(" reason=").append(state.getCancelReason());
  payload.append("] ");
 }
 private void appendToolDetails(StringBuilder payload,AiMessage message){
  if(StringUtils.isNotBlank(message.getToolName()))payload.append(" [").append(message.getToolName()).append(" ").append(StringUtils.defaultString(message.getToolArguments())).append("]");
 }
 public int estimate(String system,String checkpoint,List<AiMessage> recent,String runtimeOverhead){long tokens=AiTokenBudget.estimate(system)+AiTokenBudget.estimate(checkpoint)+AiTokenBudget.estimate(runtimeOverhead);for(AiMessage m:recent)tokens+=AiTokenBudget.estimate(m.getContent())+AiTokenBudget.estimate(m.getToolArguments())+16;return (int)Math.min(Integer.MAX_VALUE,Math.max(1,tokens+512));}
 private String safe(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.substring(0,Math.min(180,s.length()));}
}

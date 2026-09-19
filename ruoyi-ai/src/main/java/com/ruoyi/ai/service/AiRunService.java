package com.ruoyi.ai.service;
import java.util.concurrent.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import jakarta.annotation.PreDestroy; import com.ruoyi.ai.domain.*; import com.ruoyi.ai.mapper.*; import com.ruoyi.ai.runtime.AgentRuntimeUsage; import com.ruoyi.common.exception.ServiceException;
@Service
public class AiRunService {
 private final AiRunMapper runs; private final AiConversationMapper conversations; private final AiPendingToolCallMapper pending; private final AiMessageService messages; private final RunLifecycleService lifecycle; private final ConcurrentHashMap<Long,Future<?>> inFlight=new ConcurrentHashMap<>(); private final ExecutorService executor=Executors.newCachedThreadPool();
 public AiRunService(AiRunMapper runs,AiConversationMapper conversations,AiPendingToolCallMapper pending,AiMessageService messages,RunLifecycleService lifecycle){this.runs=runs;this.conversations=conversations;this.pending=pending;this.messages=messages;this.lifecycle=lifecycle;}
 @Transactional public AiRun start(AiConversation c,String clientRunKey,Long modelId,String modelCode,String effort,Integer systemVersion,Integer compactionVersion){return startLocked(c,clientRunKey,modelId,modelCode,effort,systemVersion,compactionVersion,null);}
 @Transactional public AiRun start(AiConversation c,String clientRunKey,Long modelId,String modelCode,String effort,Integer systemVersion,Integer compactionVersion,String userMessage){return startLocked(c,clientRunKey,modelId,modelCode,effort,systemVersion,compactionVersion,userMessage);}
 private AiRun startLocked(AiConversation c,String clientRunKey,Long modelId,String modelCode,String effort,Integer systemVersion,Integer compactionVersion,String userMessage){
   conversations.lockById(c.getConversationId());AiRun old=runs.selectActiveByConversation(c.getConversationId());
   AiRun n=new AiRun();n.setClientRunKey(normalizeClientRunKey(clientRunKey));n.setConversationId(c.getConversationId());n.setUserId(c.getUserId());n.setModelId(modelId);n.setModelCode(modelCode);n.setReasoningEffort(effort);n.setStatus(AiRunStatus.RUNNING.name());n.setSystemPromptVersion(systemVersion);n.setCompactionPromptVersion(compactionVersion);runs.insert(n);
   if(userMessage!=null){AiMessage m=new AiMessage();m.setConversationId(c.getConversationId());m.setRunId(n.getRunId());m.setRole("USER");m.setContent(userMessage);m.setModelId(modelId);m.setModelCode(modelCode);m.setReasoningEffort(effort);messages.append(m);}
   if(old!=null){if(lifecycle.supersede(old.getRunId(),n.getRunId()))pending.cancelByRun(old.getRunId(),"SUPERSEDED");Future<?> f=inFlight.remove(old.getRunId());if(f!=null)f.cancel(true);}
   return n;
 }
 public AiRun requireOwned(Long runId,Long userId){AiRun r=runs.selectById(runId);if(r==null||!userId.equals(r.getUserId()))throw new ServiceException("Run 不存在或无权访问");return r;}
 public AiRun get(Long runId){return runs.selectById(runId);} public AiRun active(Long conversationId){return runs.selectActiveByConversation(conversationId);} public AiRun byClientKey(String key,Long userId){AiRun r=runs.selectByClientKey(key,userId);if(r==null)throw new ServiceException("Run 不存在或无权访问");return r;}
 public boolean runnable(Long runId){return lifecycle.isRunnable(runId);}
 public boolean beginCompaction(Long runId){return lifecycle.tryBeginCompaction(runId);}
 public boolean endCompaction(Long runId){return lifecycle.tryEndCompaction(runId);}
 public void resumeTool(Long runId){if(!lifecycle.tryResumeTool(runId))throw new ServiceException("当前 Run 已停止或被新指令替代");}
 public void waitingTool(Long runId){if(!lifecycle.tryWaitingTool(runId))throw new ServiceException("当前 Run 已停止或被新指令替代");}
 public void complete(Long runId){lifecycle.tryComplete(runId);inFlight.remove(runId);}
 public void fail(Long runId,String reason){lifecycle.tryFail(runId,reason);inFlight.remove(runId);}
 public AiRun cancel(Long runId,Long userId,String reason){AiRun current=requireOwned(runId,userId);AiRunStatus status=AiRunStatus.from(current.getStatus());if(status.isTerminal())return current;if(lifecycle.requestCancel(runId,reason)){pending.cancelByRun(runId,"CANCELLED");Future<?> f=inFlight.remove(runId);if(f!=null)f.cancel(true);lifecycle.finishCancel(runId);}return runs.selectById(runId);} public AiRun cancelByClientKey(String key,Long userId,String reason){AiRun r=byClientKey(key,userId);return cancel(r.getRunId(),userId,reason);}
 public int cancelAllOwned(Long userId,String reason){int count=0;for(AiRun r:runs.selectActiveByUser(userId)){cancel(r.getRunId(),userId,reason);count++;}return count;}
 public <T> T call(Long runId,Callable<T> action) throws Exception {
  if(!runnable(runId))throw new InterruptedException("run is not active");
  Future<T> f=executor.submit(action);inFlight.put(runId,f);
  if(!runnable(runId)){f.cancel(true);inFlight.remove(runId,f);throw new InterruptedException("run cancelled before model call");}
  try{return f.get();}catch(CancellationException e){throw new InterruptedException("run cancelled");}finally{inFlight.remove(runId,f);}
 }
 public void recordUsage(Long runId,AgentRuntimeUsage u){if(u==null)return;runs.updateUsage(runId,u.inputTokens(),u.cacheReadTokens(),u.cacheWriteTokens(),u.totalTokens());}
 private String normalizeClientRunKey(String key){String value=key==null?"":key.trim();if(value.isEmpty())value=java.util.UUID.randomUUID().toString();if(value.length()>64)throw new ServiceException("clientRunKey 过长");return value;}
 @PreDestroy public void shutdown(){executor.shutdownNow();}
}

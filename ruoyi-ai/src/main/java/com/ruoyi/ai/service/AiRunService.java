package com.ruoyi.ai.service;
import java.lang.reflect.Method; import java.util.concurrent.*; import org.springframework.ai.chat.model.ChatResponse; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import jakarta.annotation.PreDestroy; import com.ruoyi.ai.domain.*; import com.ruoyi.ai.mapper.*; import com.ruoyi.common.exception.ServiceException;
@Service
public class AiRunService {
 private final AiRunMapper runs; private final AiConversationMapper conversations; private final AiPendingToolCallMapper pending; private final ConcurrentHashMap<Long,Future<?>> inFlight=new ConcurrentHashMap<>(); private final ExecutorService executor=Executors.newCachedThreadPool();
 public AiRunService(AiRunMapper runs,AiConversationMapper conversations,AiPendingToolCallMapper pending){this.runs=runs;this.conversations=conversations;this.pending=pending;}
 @Transactional public AiRun start(AiConversation c,String clientRunKey,Long modelId,String modelCode,String effort,Integer systemVersion,Integer compactionVersion){
   conversations.lockById(c.getConversationId());AiRun old=runs.selectActiveByConversation(c.getConversationId());
   AiRun n=new AiRun();n.setClientRunKey(normalizeClientRunKey(clientRunKey));n.setConversationId(c.getConversationId());n.setUserId(c.getUserId());n.setModelId(modelId);n.setModelCode(modelCode);n.setReasoningEffort(effort);n.setStatus("RUNNING");n.setSystemPromptVersion(systemVersion);n.setCompactionPromptVersion(compactionVersion);runs.insert(n);
   if(old!=null){runs.supersede(old.getRunId(),n.getRunId());pending.cancelByRun(old.getRunId(),"SUPERSEDED");Future<?> f=inFlight.remove(old.getRunId());if(f!=null)f.cancel(true);}
   return n;
 }
 public AiRun requireOwned(Long runId,Long userId){AiRun r=runs.selectById(runId);if(r==null||!userId.equals(r.getUserId()))throw new ServiceException("Run 不存在或无权访问");return r;}
 public AiRun get(Long runId){return runs.selectById(runId);} public AiRun active(Long conversationId){return runs.selectActiveByConversation(conversationId);} public AiRun byClientKey(String key,Long userId){AiRun r=runs.selectByClientKey(key,userId);if(r==null)throw new ServiceException("Run 不存在或无权访问");return r;}
 public boolean runnable(Long runId){AiRun r=runs.selectById(runId);return r!=null&&"RUNNING".equals(r.getStatus());}
 public void resumeTool(Long runId){if(runs.updateActiveState(runId,"RUNNING")!=1)throw new ServiceException("当前 Run 已停止或被新指令替代");}
 public void waitingTool(Long runId){if(runs.updateActiveState(runId,"WAITING_TOOL")!=1)throw new ServiceException("当前 Run 已停止或被新指令替代");}
 public void complete(Long runId){runs.complete(runId);inFlight.remove(runId);}
 public void fail(Long runId,String reason){runs.fail(runId,reason);inFlight.remove(runId);}
 public AiRun cancel(Long runId,Long userId,String reason){requireOwned(runId,userId);runs.updateOwnedState(runId,userId,"CANCELLED",reason);pending.cancelByRun(runId,"CANCELLED");Future<?> f=inFlight.remove(runId);if(f!=null)f.cancel(true);return runs.selectById(runId);} public AiRun cancelByClientKey(String key,Long userId,String reason){AiRun r=byClientKey(key,userId);return cancel(r.getRunId(),userId,reason);}
 public ChatResponse call(Long runId,Callable<ChatResponse> action) throws Exception {Future<ChatResponse> f=executor.submit(action);inFlight.put(runId,f);try{return f.get();}catch(CancellationException e){throw new InterruptedException("run cancelled");}finally{inFlight.remove(runId,f);}}
 public void recordUsage(Long runId,ChatResponse response){if(response==null||response.getMetadata()==null||response.getMetadata().getUsage()==null)return;Object u=response.getMetadata().getUsage();long input=read(u,"getPromptTokens","getInputTokens");long read=read(u,"getCacheReadInputTokens");long write=read(u,"getCacheWriteInputTokens");long total=read(u,"getTotalTokens");runs.updateUsage(runId,input,read,write,total);}
 private String normalizeClientRunKey(String key){String value=key==null?"":key.trim();if(value.isEmpty())value=java.util.UUID.randomUUID().toString();if(value.length()>64)throw new ServiceException("clientRunKey 过长");return value;}
 private long read(Object target,String... names){for(String n:names)try{Method m=target.getClass().getMethod(n);Object v=m.invoke(target);if(v instanceof Number x)return x.longValue();}catch(Exception ignored){}return 0;}
 @PreDestroy public void shutdown(){executor.shutdownNow();}
}
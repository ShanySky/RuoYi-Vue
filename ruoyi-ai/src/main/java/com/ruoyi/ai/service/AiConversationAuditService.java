package com.ruoyi.ai.service;
import java.util.*;import org.springframework.stereotype.Service;import com.ruoyi.ai.domain.*;import com.ruoyi.ai.mapper.*;import com.ruoyi.common.exception.ServiceException;
@Service public class AiConversationAuditService{
 private final AiConversationAuditMapper audit;private final AiConversationMapper conversations;private final AiMessageMapper messages;private final AiRunMapper runs;private final AiPendingToolCallMapper pending;private final AiCheckpointMapper checkpoints;private final AiPromptVersionMapper promptVersions;
 public AiConversationAuditService(AiConversationAuditMapper a,AiConversationMapper c,AiMessageMapper m,AiRunMapper r,AiPendingToolCallMapper p,AiCheckpointMapper cp,AiPromptVersionMapper pv){audit=a;conversations=c;messages=m;runs=r;pending=p;checkpoints=cp;promptVersions=pv;}
 public List<Map<String,Object>> list(Long u,Long c,Long m,String s,String t,String b,String e){return audit.list(u,c,m,s,t,b,e);}
 public Map<String,Object> detail(Long id){
  AiConversation c=conversations.selectById(id);if(c==null)throw new ServiceException("会话不存在");
  List<AiRun> runList=runs.selectByConversation(id);Map<String,AiPromptVersion> referenced=new LinkedHashMap<>();
  for(AiRun run:runList){addVersion(referenced,AiPromptService.SYSTEM,run.getSystemPromptVersion());addVersion(referenced,AiPromptService.COMPACTION,run.getCompactionPromptVersion());}
  Map<String,Object>v=new LinkedHashMap<>();v.put("conversation",c);v.put("messages",messages.selectByConversationId(id));v.put("runs",runList);v.put("pendingTools",pending.selectByConversation(id));v.put("checkpoints",checkpoints.selectByConversation(id));v.put("promptVersions",new ArrayList<>(referenced.values()));return v;
 }
 private void addVersion(Map<String,AiPromptVersion> target,String type,Integer versionNo){if(versionNo==null)return;String key=type+":"+versionNo;if(target.containsKey(key))return;AiPromptVersion version=promptVersions.selectByTypeAndVersion(type,versionNo);if(version!=null)target.put(key,version);}
}

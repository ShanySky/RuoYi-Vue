package com.ruoyi.ai.service;
import java.util.*;import org.springframework.stereotype.Service;import com.ruoyi.ai.domain.*;import com.ruoyi.ai.mapper.*;import com.ruoyi.common.exception.ServiceException;
@Service public class AiConversationAuditService{
 private final AiConversationAuditMapper audit;private final AiConversationMapper conversations;private final AiMessageMapper messages;private final AiRunMapper runs;private final AiPendingToolCallMapper pending;private final AiCheckpointMapper checkpoints;private final AiPromptVersionMapper promptVersions;
 private final com.ruoyi.ai.server.AiBusinessHistoryGuard historyGuard;
 public AiConversationAuditService(AiConversationAuditMapper a,AiConversationMapper c,AiMessageMapper m,AiRunMapper r,AiPendingToolCallMapper p,AiCheckpointMapper cp,AiPromptVersionMapper pv,com.ruoyi.ai.server.AiBusinessHistoryGuard historyGuard){audit=a;conversations=c;messages=m;runs=r;pending=p;checkpoints=cp;promptVersions=pv;this.historyGuard=historyGuard;}
 public List<Map<String,Object>> list(Long u,Long c,Long m,String s,String t,String b,String e){return audit.list(u,c,m,s,t,b,e);}
 public Map<String,Object> detail(Long id){
  AiConversation c=conversations.selectById(id);if(c==null)throw new ServiceException("会话不存在");
  // 审计权限可以看运行事实，不能自动获得他人业务数据内容。
  if(historyGuard.protectedHistory(id)){boolean readable=c.getUserId().equals(com.ruoyi.common.utils.SecurityUtils.getUserId());if(readable){try{historyGuard.requireReadable(id);}catch(ServiceException denied){readable=false;}}if(!readable){Map<String,Object> protectedView=new LinkedHashMap<>();protectedView.put("conversation",c);protectedView.put("runs",runs.selectByConversation(id));protectedView.put("messages",List.of());protectedView.put("pendingTools",List.of());protectedView.put("checkpoints",List.of());protectedView.put("promptVersions",List.of());protectedView.put("protectedResults",true);return protectedView;}}
  List<AiRun> runList=runs.selectByConversation(id);Map<String,AiPromptVersion> referenced=new LinkedHashMap<>();
  for(AiRun run:runList){addVersion(referenced,AiPromptService.SYSTEM,run.getSystemPromptVersion());addVersion(referenced,AiPromptService.COMPACTION,run.getCompactionPromptVersion());}
  Map<String,Object>v=new LinkedHashMap<>();v.put("conversation",c);v.put("messages",messages.selectByConversationId(id));v.put("runs",runList);v.put("pendingTools",pending.selectByConversation(id));v.put("checkpoints",checkpoints.selectByConversation(id));v.put("promptVersions",new ArrayList<>(referenced.values()));return v;
 }
 private void addVersion(Map<String,AiPromptVersion> target,String type,Integer versionNo){if(versionNo==null)return;String key=type+":"+versionNo;if(target.containsKey(key))return;AiPromptVersion version=promptVersions.selectByTypeAndVersion(type,versionNo);if(version!=null)target.put(key,version);}
}

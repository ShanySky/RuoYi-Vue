package com.ruoyi.ai.service;
import java.util.*; import org.springframework.stereotype.Service; import com.ruoyi.ai.domain.*; import com.ruoyi.ai.mapper.*; import com.ruoyi.common.exception.ServiceException; import com.ruoyi.common.utils.SecurityUtils; import com.ruoyi.common.utils.StringUtils;
@Service
public class AiConversationService {
 private final AiConversationMapper conversations; private final AiMessageMapper messages; private final AiRunMapper runs; private final AiPendingToolCallMapper pending; private final AiCheckpointMapper checkpoints; private final AiRunService runService;
 public AiConversationService(AiConversationMapper c,AiMessageMapper m,AiRunMapper r,AiPendingToolCallMapper p,AiCheckpointMapper cp,AiRunService rs){conversations=c;messages=m;runs=r;pending=p;checkpoints=cp;runService=rs;}
 public List<AiConversation> list(String keyword){return conversations.selectByUser(SecurityUtils.getUserId(),StringUtils.trim(keyword));}
 public AiConversation last(){return conversations.selectLastByUser(SecurityUtils.getUserId());}
 public Map<String,Object> detail(Long id){Long uid=SecurityUtils.getUserId();AiConversation c=conversations.selectById(id);if(c==null||!uid.equals(c.getUserId())||"DELETED".equals(c.getStatus()))throw new ServiceException("会话不存在或无权访问");Map<String,Object> v=new LinkedHashMap<>();v.put("conversation",c);v.put("messages",messages.selectByConversationId(id));v.put("activeRun",runs.selectActiveByConversation(id));v.put("pendingTools",pending.selectPendingByConversation(id));v.put("checkpoint",checkpoints.selectLatest(id));return v;}
 public void rename(Long id,String title){Long uid=SecurityUtils.getUserId();if(StringUtils.isBlank(title)||title.trim().length()>200)throw new ServiceException("会话标题不能为空且不能超过 200 字");if(conversations.updateTitle(id,uid,title.trim())!=1)throw new ServiceException("会话不存在或无权访问");}
 public void archive(Long id){cancelActive(id);if(conversations.updateStatus(id,SecurityUtils.getUserId(),"ARCHIVED")!=1)throw new ServiceException("会话不存在或无权访问");}
 public void delete(Long id){cancelActive(id);if(conversations.updateStatus(id,SecurityUtils.getUserId(),"DELETED")!=1)throw new ServiceException("会话不存在或无权访问");}
 public void cancelActive(Long id){Long uid=SecurityUtils.getUserId();AiConversation c=conversations.selectById(id);if(c==null||!uid.equals(c.getUserId()))throw new ServiceException("会话不存在或无权访问");AiRun active=runs.selectActiveByConversation(id);if(active!=null)runService.cancel(active.getRunId(),uid,"CONVERSATION_CLOSED");}
}
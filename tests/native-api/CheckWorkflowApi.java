import jadx.api.*;
import java.io.File;
import java.util.*;
/** Check DEX names, independently of JADX's renamed display aliases. */
public class CheckWorkflowApi {
 public static void main(String[] args) throws Exception {
  String p="com.hihonor.magicvoice.yoyotask.shared.";
  Map<String,List<String>> required=new LinkedHashMap<>();
  required.put(p+"workflow.model.BrainMcpResult",Arrays.asList("isSuccess()Z","getSucceededIds()Ljava/util/List;"));
  required.put(p+"workflow.model.Task",Arrays.asList("getExtMap()Lcom/hihonor/magicvoice/yoyotask/shared/workflow/model/ExtMapBean;"));
  required.put(p+"workflow.model.ExtMapBean",Arrays.asList("getWorkflowId()Ljava/lang/String;"));
  required.put(p+"workflow.WorkFlowUseCase",Arrays.asList("addOrUpdateWorkFlow(Ljava/util/List;Ljava/util/Map;Lcom/hihonor/magicvoice/yoyotask/shared/workflow/WorkFlowUseCase$WorkFlowListener;)V","getWorkFlowListStrictly(Lcom/hihonor/magicvoice/yoyotask/shared/workflow/WorkFlowUseCase$WorkFlowListener;Ljava/lang/Runnable;)V"));
  required.put(p+"chat.WorkFlowCardDisplayHelper",Arrays.asList("showTrialRunCard(Ljava/util/Map;Lcom/hihonor/magicvoice/yoyotask/shared/workflow/model/Task;Ljava/lang/String;)V"));
  required.put(p+"utils.WorkFlowRequestUtil",Arrays.asList("setHasWorkFlow(Z)V"));
  JadxArgs cfg=new JadxArgs();cfg.setInputFiles(Collections.singletonList(new File(args[0])));cfg.setSkipResources(true);cfg.setThreadsCount(1);
  int checked=0;
  try(JadxDecompiler d=new JadxDecompiler(cfg)){d.load();
   for(Map.Entry<String,List<String>> e:required.entrySet()){
    JavaClass c=d.searchJavaClassOrItsParentByOrigFullName(e.getKey());if(c==null)throw new AssertionError("missing class: "+e.getKey());
    Set<String> methods=new HashSet<>();
    for(JavaMethod m:c.getMethods()) if(m.getAccessFlags().isPublic())methods.add(m.getMethodNode().getMethodInfo().getShortId());
    for(String method:e.getValue()){if(!methods.contains(method))throw new AssertionError(e.getKey()+" missing JVM method "+method);checked++;}
   }
  }
  System.out.println("native DEX API: "+checked+" exact JVM signatures PASS");
 }
}

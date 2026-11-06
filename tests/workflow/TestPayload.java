package dev.yoyobridge.helper;
import java.nio.file.*;
import java.util.*;
import org.json.*;
public class TestPayload {
 public static void main(String[] args) throws Exception {
  JSONObject raw=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"));
  JSONObject tools=NativeWorkflowCapabilities.extractTools(raw);
  JSONObject caps=new JSONObject().put("tools",tools);
  Calendar now=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
  now.set(2026,Calendar.NOVEMBER,6,7,59,59);now.set(Calendar.MILLISECOND,0);
  JSONObject p=NativeWorkflowExecutor.buildPayload(caps,8,0,"测试应用","test.app","测试目标",now);
  JSONObject task=p.getJSONArray("tasks").getJSONObject(0);
  String time=task.getJSONObject("parameters").getJSONArray("triggers").getJSONObject(0).getJSONObject("parameters").getString("start_datetime");
  JSONObject infer=new JSONObject(time).getJSONObject("inferResult");
  if(!"2026-11-06 08:00:00".equals(infer.getString("dtstart")))throw new AssertionError("timezone/today");
  now.set(Calendar.HOUR_OF_DAY,8);now.set(Calendar.MINUTE,0);now.set(Calendar.SECOND,0);
  JSONObject next=NativeWorkflowExecutor.buildPayload(caps,8,0,"测试应用","test.app","测试目标",now);
  JSONObject t=next.getJSONArray("tasks").getJSONObject(0);
  String tomorrow=t.getJSONObject("parameters").getJSONArray("triggers").getJSONObject(0).getJSONObject("parameters").getString("start_datetime");
  if(!"2026-11-07 08:00:00".equals(new JSONObject(tomorrow).getJSONObject("inferResult").getString("dtstart")))throw new AssertionError("past trigger must advance");
  if(p.getString("planBoardId").equals(next.getString("planBoardId")))throw new AssertionError("fresh plan id");
  String id=task.getJSONObject("extMap").getString("workflowId");
  if(!id.matches("[1-9][0-9]{25}")||id.equals(t.getJSONObject("extMap").getString("workflowId")))throw new AssertionError("workflow identity format/isolation");
  JSONObject history=new JSONArray(task.getJSONObject("extMap").getString("workflowRaw")).getJSONObject(0);
  if(!id.equals(history.getString("id"))||!"测试目标".equals(history.getJSONObject("input").getString("actions")))throw new AssertionError("task history identity/current query");
  if(p.toString().contains("OLD_FIXTURE_ID")||p.toString().contains("OLD_DSL"))throw new AssertionError("prior task copied");
  JSONObject gui=t.getJSONObject("parameters").getJSONArray("actions").getJSONObject(0);
  if(!"测试目标".equals(gui.getJSONObject("mcpCall").getJSONObject("arguments").getString("task_query")))throw new AssertionError("query mismatch");
  for(int[] hm:new int[][]{{0,0},{23,59}}) {
   JSONObject result=NativeWorkflowExecutor.buildPayload(caps,hm[0],hm[1],"a","test.app","q",now);
   if(result.getJSONArray("tasks").length()!=1)throw new AssertionError("task count");
  }
  System.out.println("daily payload contract: timezone, next occurrence, IDs, fresh query and boundary times PASS");
 }
}

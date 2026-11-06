package dev.yoyobridge.helper;
import java.nio.file.*;
import org.json.*;
public class TestMetadata {
 public static void main(String[] args) throws Exception {
  JSONObject input=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"));
  JSONObject payload=input.getJSONObject("data").getJSONObject("event").getJSONObject("payload");
  JSONObject task=payload.getJSONArray("tasks").getJSONObject(0);
  JSONObject gui=task.getJSONObject("parameters").getJSONArray("actions").getJSONObject(0);
  gui.getJSONObject("executeParams").put("membershipInfo",new JSONObject().put("token","NEVER_AUTH"));
  gui.getJSONObject("executeParams").put("skillDesc","NEVER_USER_QUERY");
  gui.getJSONObject("mcpCall").getJSONObject("arguments").put("task_query","NEVER_USER_QUERY");
  JSONObject tools=NativeWorkflowCapabilities.extractTools(input);
  if(tools==null||tools.length()!=3)throw new AssertionError("missing native capability");
  String saved=tools.toString();
  for(String forbidden:new String[]{"NEVER_AUTH","NEVER_USER_QUERY","workflowId","workflowRaw","start_datetime","skillDesc"})
   if(saved.contains(forbidden))throw new AssertionError("private or dynamic field retained: "+forbidden);
  if(!tools.getJSONObject("gui_task_create").getString("toolId").equals(gui.getString("toolId")))throw new AssertionError("tool identity changed");
  gui.getJSONObject("executeParams").put("toolDeps",new JSONObject().put("required","unknown"));
  if(NativeWorkflowCapabilities.extractTools(input)!=null)throw new AssertionError("unsupported dependencies accepted");
  input.getJSONObject("data").getJSONObject("event").getJSONObject("header").put("namespace","Other");
  if(NativeWorkflowCapabilities.extractTools(input)!=null)throw new AssertionError("unrelated event accepted");
  System.out.println("metadata extraction: native shape, dynamic-field removal, credential removal, dependencies, event scope PASS");
 }
}

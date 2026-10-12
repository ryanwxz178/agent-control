package dev.orca.pocket;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Phone transport fixture replay; no SSH connection or model request. */
public final class TeleAgentSessionTest {
    private static int checks;
    private static void check(boolean value,String label){
        if(!value)throw new AssertionError(label);
        checks++;System.out.println("PASS "+label);
    }
    private interface Attempt {void run()throws Exception;}
    private static void refuses(Attempt attempt,Class<? extends Exception> expected,String label)throws Exception{
        try{attempt.run();throw new AssertionError(label+" did not refuse");}
        catch(Exception error){if(!expected.isInstance(error))throw error;check(true,label);}
    }
    private static JSONObject event(String type){return new JSONObject().put("type","teleagent."+type).put("sessionId","owned-session");}
    private static JSONObject request(String type,String id){return event(type).put("request",new JSONObject().put("id",id));}
    private static JSONObject q(String question,boolean multiple,boolean custom,String...labels){
        JSONArray options=new JSONArray();for(String label:labels)options.put(new JSONObject().put("label",label).put("description","说明 "+label));
        return new JSONObject().put("header","偏好").put("question",question).put("multiple",multiple).put("custom",custom).put("options",options);
    }
    private static final class Harness {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final List<JSONObject> cards=new ArrayList<>(),states=new ArrayList<>(),files=new ArrayList<>(),extras=new ArrayList<>();
        final Interactions interactions=new Interactions((type,value)->{
            JSONObject copy=new JSONObject(value.toString());
            if(type.equals("interaction"))cards.add(copy);else states.add(copy);
        });
        final Attachments attachments=new Attachments((type,value)->files.add(new JSONObject(value.toString())));
        final EventDecoder decoder=new EventDecoder((text,status)->{});
        final TeleAgentSession protocol;
        Harness()throws Exception{
            decoder.extra=value->extras.add(new JSONObject(value.toString()));
            protocol=new TeleAgentSession("test-run","你好\nfixture","/tmp/中文 folder","host","TeleAgent",false,
                decoder,interactions,attachments);
            protocol.start(output);
        }
        void bind()throws Exception{accept(event("session"));}
        boolean accept(JSONObject value)throws Exception{return protocol.accept(value.toString());}
        JSONObject lastCard(){return cards.get(cards.size()-1);}
        void respond(String choice)throws Exception{interactions.respond(lastCard().getString("token"),new JSONObject().put("choice",choice));}
        void text(String value)throws Exception{
            JSONObject card=lastCard();String field=card.getJSONArray("inputs").getJSONObject(0).getString("id");
            interactions.respond(card.getString("token"),new JSONObject().put("choice","__desktop_text__")
                .put("textValues",new JSONObject().put(field,value)));
        }
        List<JSONObject> sent()throws Exception{
            List<JSONObject> values=new ArrayList<>();
            for(String line:output.toString("UTF-8").split("\n"))if(!line.trim().isEmpty())values.add(new JSONObject(line));
            return values;
        }
        JSONObject lastSent()throws Exception{List<JSONObject> values=sent();return values.get(values.size()-1);}
    }

    private static void coreFixtures()throws Exception{
        Harness h=new Harness();JSONObject start=h.lastSent();
        check(start.getString("op").equals("start")&&!start.getBoolean("write")&&
            start.getString("prompt").equals("你好\nfixture")&&start.getString("directory").equals("/tmp/中文 folder"),
            "start is one UTF-8 NDJSON frame with prompt directory and write policy");
        h.accept(event("snapshot").put("text","unbound"));h.accept(event("done").put("text","unbound"));
        check(h.decoder.text().isEmpty()&&!h.decoder.completed,"unbound events cannot change phone state");
        h.bind();h.bind();h.accept(event("session").put("sessionId","foreign"));
        check(h.extras.size()==1&&h.extras.get(0).getString("protocol").equals("teleagent-http"),
            "session establishes one teleagent-http identity and never rebinds");
        h.accept(event("snapshot").put("sessionId","foreign").put("text","foreign"));
        h.accept(new JSONObject().put("type","teleagent.snapshot").put("text","missing"));
        h.accept(event("error").put("sessionId","foreign").put("message","Authentication required"));
        check(h.decoder.text().isEmpty()&&!h.decoder.failed,"foreign and missing session snapshots and foreign errors are ignored");
        h.accept(event("snapshot").put("text","first reply"));
        check(h.decoder.workStarted&&h.decoder.text().equals("first reply"),"owned snapshot replaces reply text");
        refuses(()->h.accept(event("done").put("text","  ")),IOException.class,"empty completion rejected");
        check(h.accept(event("done").put("text","final reply"))&&h.decoder.completed&&h.decoder.text().equals("final reply"),
            "owned nonempty final snapshot completes exactly one turn");
        check(!h.accept(event("snapshot").put("text","after done"))&&h.decoder.text().equals("final reply"),
            "events after completion cannot mutate final reply");
    }

    private static void permissionFixtures()throws Exception{
        Harness h=new Harness();h.bind();
        JSONObject p=request("permission","permission-1");
        p.getJSONObject("request").put("permission","edit").put("patterns",new JSONArray().put("/tmp/result.txt"))
            .put("metadata",new JSONObject().put("description","fixture operation"));
        h.accept(new JSONObject(p.toString()).put("sessionId","other"));check(h.cards.isEmpty(),"foreign permission cannot publish a card");
        h.accept(p);h.accept(p);
        check(h.cards.size()==1&&h.lastCard().getJSONArray("options").length()==2&&
            h.lastCard().getJSONArray("options").getJSONObject(0).getString("id").equals("once"),
            "permission publishes once/reject options and repeated requests do not duplicate cards");
        refuses(()->h.accept(event("done").put("text","premature")),IOException.class,"completion cannot bypass an unanswered permission");
        final String token=h.lastCard().getString("token");h.respond("once");JSONObject reply=h.lastSent();
        check(reply.getString("op").equals("permission")&&reply.getString("sessionId").equals("owned-session")&&
            reply.getString("requestId").equals("permission-1")&&reply.getString("reply").equals("once"),
            "approval keeps original request id and owned session");
        refuses(()->h.interactions.respond(token,new JSONObject().put("choice","once")),IllegalStateException.class,
            "phone double tap cannot repeat permission response");
        h.accept(p);check(h.sent().size()==2&&h.cards.size()==1,"responded permission replay cannot run a second action");
        check(h.accept(event("done").put("text","approved reply")),"answered permission allows completion");

        Harness closed=new Harness();closed.bind();closed.accept(p);final String oldToken=closed.lastCard().getString("token");
        closed.accept(event("interaction.closed").put("requestId","permission-1"));
        refuses(()->closed.interactions.respond(oldToken,new JSONObject().put("choice","once")),IllegalStateException.class,
            "backend closure invalidates phone capability");
        check(closed.accept(event("done").put("text","server resolved")),"authoritative closure ends an outstanding request");
    }

    private static void questionFixtures()throws Exception{
        Harness h=new Harness();h.bind();JSONObject questions=request("question","question-1");
        questions.getJSONObject("request").put("questions",new JSONArray().put(q("选哪个？",false,true,"甲","乙"))
            .put(q("填写细节？",false,true)));
        h.accept(questions);h.accept(questions);
        check(h.cards.size()==1&&h.lastCard().optBoolean("desktop")&&h.lastCard().getJSONArray("inputs").length()==1,
            "question uses existing option and custom input controls with one card per question");
        h.respond("teleagent_option_1");
        check(h.cards.size()==2&&h.sent().size()==1,"first answer publishes next card without submitting partial answers");
        refuses(()->h.accept(event("done").put("text","premature")),IOException.class,"incomplete multi-question request blocks completion");
        h.text("中文回答");JSONObject reply=h.lastSent();JSONArray answers=reply.getJSONArray("answers");
        check(reply.getString("op").equals("question")&&reply.getString("requestId").equals("question-1")&&
            answers.length()==2&&answers.getJSONArray(0).getString(0).equals("乙")&&answers.getJSONArray(1).getString(0).equals("中文回答"),
            "all ordered answers are returned as arrays preserving original option labels");

        Harness multi=new Harness();multi.bind();JSONObject m=request("question","multi");
        m.getJSONObject("request").put("questions",new JSONArray().put(q("多选？",true,false,"甲","乙")));
        multi.accept(m);check(multi.lastCard().getString("detail").contains("多选"),"multi-select explains comma-separated input explicitly");
        refuses(()->multi.text("未知选项"),IllegalArgumentException.class,"custom-disabled multi-select never guesses an unknown choice");
        check(multi.cards.size()==2&&multi.sent().size()==1,"invalid answer gets a new one-shot card and sends no answer");
        multi.text("甲，乙");JSONArray selected=multi.lastSent().getJSONArray("answers").getJSONArray(0);
        check(selected.length()==2&&selected.getString(0).equals("甲")&&selected.getString(1).equals("乙"),
            "multi-select input supports multiple exact labels and Chinese commas");

        Harness cancel=new Harness();cancel.bind();cancel.accept(questions);cancel.respond("teleagent_question_reject");
        check(cancel.lastSent().getString("op").equals("question-reject")&&cancel.lastSent().getString("requestId").equals("question-1"),
            "question cancellation rejects original request without default answers");
    }

    private static void outputAndAuthFixtures()throws Exception{
        Harness h=new Harness();h.bind();
        JSONObject generated=new JSONObject().put("type","image_gen_tool_result").put("status","completed")
            .put("images",new JSONArray().put(new JSONObject().put("localPath","/tmp/teleagent-result.png")));
        JSONObject tool=event("tool").put("title","生成图片").put("status","failed").put("output",generated)
            .put("attachments",new JSONArray().put(new JSONObject().put("type","resource_link").put("uri","file:///tmp/result.txt")));
        h.accept(tool);check(h.files.isEmpty(),"failed tool cannot create output attachment capabilities");
        h.accept(new JSONObject(tool.toString()).put("sessionId","foreign").put("status","completed"));
        check(h.files.isEmpty(),"foreign tool output cannot create attachments");
        h.accept(tool.put("status","completed"));check(h.files.size()==2&&h.attachments.has("test-run"),
            "completed image generation and explicit resource link produce phone attachments");
        h.accept(event("tool").put("status","completed").put("attachments",new JSONArray()
            .put(new JSONObject().put("type","file").put("mime","image/png").put("filename","fixture.png").put("url","file:///tmp/fixture.png"))
            .put(new JSONObject().put("type","file").put("url","https://untrusted.example/fixture.png"))));
        check(h.files.size()==3,"OpenCode FilePart local URL becomes attachment while arbitrary remote URL is ignored");

        Harness early=new Harness();
        refuses(()->early.protocol.accept(new JSONObject().put("type","teleagent.error").put("code","teleagent_owner_token_missing")
            .put("message","Credentials unavailable").toString()),IOException.class,"pre-session auth refusal surfaces IOException");
        check(early.decoder.failed&&early.decoder.text().equals("Authentication required")&&!early.decoder.workStarted,
            "pre-submission auth refusal marks the established desktop fallback signal");
        Harness submitted=new Harness();submitted.bind();
        refuses(()->submitted.accept(event("error").put("submitted",true).put("message","Authentication required")),IOException.class,
            "post-submission auth failure surfaces without replay");
        check(!submitted.decoder.failed,"submitted auth failure cannot set the fallback authentication signal");
        check(submitted.decoder.workStarted&&!PocketEngine.canFallback(submitted.decoder,new IOException("TeleAgent：Authentication required")),
            "main engine cannot fall back using submitted error exception text");
        Harness missingSession=new Harness();
        refuses(()->missingSession.protocol.accept(new JSONObject().put("type","teleagent.error").put("submitted",true)
            .put("message","Authentication required").toString()),IOException.class,"submitted error without identity remains a failure");
        check(missingSession.decoder.workStarted&&!PocketEngine.canFallback(missingSession.decoder,new IOException("Authentication required")),
            "submitted flag overrides absence of a session and blocks replay");
    }

    private static void replay(Path file)throws Exception{
        Harness h=new Harness();boolean done=false;
        String content=new String(Files.readAllBytes(file),StandardCharsets.UTF_8).trim();
        if(content.startsWith("[")){
            JSONArray events=new JSONArray(content);
            for(int i=0;i<events.length();i++)done|=h.protocol.accept(events.getJSONObject(i).toString());
        }else for(String line:content.split("\n"))if(!line.trim().isEmpty())done|=h.protocol.accept(line);
        check(done&&h.decoder.completed&&!h.decoder.text().trim().isEmpty(),"observed bridge fixture completes through Java transport");
    }

    public static void main(String[] args)throws Exception{
        coreFixtures();permissionFixtures();questionFixtures();outputAndAuthFixtures();
        if(args.length>0)replay(Paths.get(args[0]));
        System.out.println("ALL "+checks+" TELEAGENT CHECKS PASSED");
    }
}

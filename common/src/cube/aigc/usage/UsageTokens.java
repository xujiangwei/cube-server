package cube.aigc.usage;

import org.json.JSONObject;

/** Presence-aware decoder: absent, fractional and negative counts are never actual zero. */
public final class UsageTokens {
    private UsageTokens() { }
    public static long[] read(JSONObject payload) {
        if(payload==null)return null;
        JSONObject usage=payload.optJSONObject("usage");
        if(usage==null)usage=payload.optJSONObject("performance");
        if(usage==null)usage=payload;
        String in=usage.has("prompt_tokens")?"prompt_tokens":usage.has("inputTokens")?"inputTokens":usage.has("tokens")?"tokens":"input";
        String out=usage.has("completion_tokens")?"completion_tokens":usage.has("outputTokens")?"outputTokens":usage.has("generatedTokens")?"generatedTokens":"output";
        try {
            if(!usage.has(in)||!usage.has(out)||usage.isNull(in)||usage.isNull(out))return null;
            long a=new java.math.BigDecimal(usage.get(in).toString()).longValueExact(),b=new java.math.BigDecimal(usage.get(out).toString()).longValueExact();
            return a<0||b<0?null:new long[]{a,b};
        }catch(RuntimeException e){return null;}
    }
}

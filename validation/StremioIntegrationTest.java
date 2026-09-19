package com.carstream.app;

import android.content.SharedPreferences;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import sun.misc.Unsafe;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Real production HTTP server/relay on the JVM; Android services and TorBox are fixtures. */
public final class StremioIntegrationTest {
    static final List<String> checks = new ArrayList<String>();
    static final Map<String,Object> preferences = new HashMap<String,Object>();
    static Unsafe unsafe;
    static int port;
    static String token;
    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks.add(description);
    }
    static void field(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance,value);
    }
    static Object get(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    static Object prefs(Class<?> type) {
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {
            String name=m.getName();
            if (name.equals("edit")) return prefs(SharedPreferences.Editor.class);
            if (name.startsWith("get")) return preferences.getOrDefault(a[0],a[1]);
            if (name.startsWith("put")) { preferences.put((String)a[0],a[1]); return p; }
            if (name.equals("remove")) {preferences.remove(a[0]);return p;}
            if (name.equals("contains")) return preferences.containsKey(a[0]);
            if (name.equals("commit")) return true;
            return null;
        });
    }
    static MediaItem item(int torrent,int file,String source,String path,boolean ready) {
        return new MediaItem(torrent+":"+file,torrent,file,source,MediaItem.displayName(path),path,1000,MediaItem.mimeFor(path),ready);
    }
    static final class Response {
        int status; String headers; byte[] body;
        JSONObject json() { return new JSONObject(new String(body,StandardCharsets.UTF_8)); }
    }
    static Response request(String method, String path, String extra) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((method+" "+path+" HTTP/1.1\r\nHost: attacker.example\r\n"+extra+"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] response=socket.getInputStream().readAllBytes();
            String text=new String(response,StandardCharsets.ISO_8859_1);int split=text.indexOf("\r\n\r\n");
            if(split<0)throw new AssertionError("No HTTP response for "+path);
            Response result=new Response(); result.headers=text.substring(0,split);
            result.status=Integer.parseInt(result.headers.split(" ")[1]);
            result.body=Arrays.copyOfRange(response,split+4,response.length);return result;
        }
    }
    static String route(String resource) { return "/stremio/"+token+"/"+resource; }
    static JSONObject json(String resource) throws Exception {
        Response r=request("GET",route(resource),"");
        if(r.status!=200)throw new AssertionError("HTTP "+r.status+" "+resource);
        return r.json();
    }
    public static void main(String[] args) throws Exception {
        Field u=Unsafe.class.getDeclaredField("theUnsafe");u.setAccessible(true);unsafe=(Unsafe)u.get(null);
        AppSettings settings=(AppSettings)unsafe.allocateInstance(AppSettings.class);
        field(settings,"preferences",prefs(SharedPreferences.class)); token=settings.getStremioToken();
        check(token.matches("[a-f0-9]{32}") && token.equals(settings.getStremioToken()),"Persistent random add-on token");
        List<MediaItem> library=Arrays.asList(
            item(22,10,"Elena.of.Avalor.S01E01.Collection","Season 1/Elena.S01E10.mp4",true),
            item(22,2,"Elena.of.Avalor.S01E01.Collection","Season 1/Elena.S01E02.mp4",true),
            item(22,1,"Elena.of.Avalor.S01E01.Collection","Season 1/Elena.S01E01.mp4",true),
            item(22,20,"Elena.of.Avalor.S01E01.Collection","Season 2/Elena.S02E01.mkv",true),
            item(22,99,"Elena.of.Avalor.S01E01.Collection","Elena.S02E02.mp4",false),
            item(30,1,"A movie","Movie & plus+ 100%.mp4",true),
            item(31,1,"A & B + C","Clip 10.mp4",true),item(31,2,"A & B + C","Clip 2.mp4",true));
        CellularNetworkProvider network=(CellularNetworkProvider)unsafe.allocateInstance(CellularNetworkProvider.class);
        TorBoxClient torbox=(TorBoxClient)unsafe.allocateInstance(TorBoxClient.class);
        field(torbox,"current",library); field(torbox,"networks",network);
        ConcurrentHashMap<String,Object> cache=new ConcurrentHashMap<String,Object>();field(torbox,"downloadUrlCache",cache);
        byte[] content=new byte[8192];for(int i=0;i<content.length;i++)content[i]=(byte)(i%251);
        HttpServer upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/video", exchange -> {
            String range=exchange.getRequestHeaders().getFirst("Range");int start=0,end=content.length-1;
            if(range!=null){String[] parts=range.substring(6).split("-");start=Integer.parseInt(parts[0]);if(parts.length>1)end=Integer.parseInt(parts[1]);}
            exchange.getResponseHeaders().add("Content-Type","video/mp4");
            exchange.getResponseHeaders().add("Accept-Ranges","bytes");
            exchange.getResponseHeaders().add("Content-Length",String.valueOf(end-start+1));
            if(range!=null)exchange.getResponseHeaders().add("Content-Range","bytes "+start+"-"+end+"/"+content.length);
            boolean head=exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(range==null?200:206,head?-1:end-start+1);
            if(!head)exchange.getResponseBody().write(content,start,end-start+1);exchange.close();
        });upstream.start();
        Class<?> cached=Class.forName("com.carstream.app.TorBoxClient$CachedDownloadUrl");
        Constructor<?> ctor=cached.getDeclaredConstructor(URL.class,long.class);ctor.setAccessible(true);
        for(MediaItem item:library)cache.put(item.id,ctor.newInstance(new URL("http://127.0.0.1:"+upstream.getAddress().getPort()+"/video"),System.currentTimeMillis()+60000));
        try(ServerSocket available=new ServerSocket(0)){port=available.getLocalPort();}
        LocalHttpServer server=new LocalHttpServer(null,port,"1234",new ClientRegistry(),torbox,network,null,null,settings,()->false);
        byte[] hls=new byte[200000];System.arraycopy("Hls".getBytes(),0,hls,0,3);field(get(server,"hlsScriptCache"),"memory",hls);
        try {
            server.start();
            check(request("GET","/stremio/wrong/manifest.json","").status==401,"Wrong or missing token cannot browse");
            check(request("GET","/stremio/manifest.json","").status==401,"Manifest has no public unauthenticated variant");
            check(request("OPTIONS",route("manifest.json"),"").status==204,"CORS preflight succeeds");
            JSONObject manifest=json("manifest.json");
            check(manifest.getString("id").equals("com.carstream.local") && manifest.getJSONArray("catalogs").length()==2,"Manifest exposes movie and series catalogs");
            Response head=request("HEAD",route("manifest.json"),"");
            check(head.status==200 && head.body.length==0 && head.headers.contains("Access-Control-Allow-Origin: *"),"HEAD and CORS on add-on JSON");
            check(request("POST",route("manifest.json"),"").status==405,"Mutation methods rejected");
            JSONArray shows=json("catalog/series/carstream.json").getJSONArray("metas");
            check(shows.length()==2 && shows.getJSONObject(0).getString("name").startsWith("Elena"),"Elena first; ready downloads grouped into shows");
            check(json("catalog/movie/carstream.json").getJSONArray("metas").length()==1,"Standalone movie catalog");
            check(json("catalog/series/carstream/search=A%20%26%20B%20%2B%20C.json").getJSONArray("metas").length()==1,"Encoded ampersand and plus survive catalog search");
            JSONObject meta=json("meta/series/carstream%3Adownload%3A22.json").getJSONObject("meta");
            JSONArray episodes=meta.getJSONArray("videos");
            check(episodes.length()==4,"Unready episode omitted");
            check(episodes.getJSONObject(1).getInt("episode")==2 && episodes.getJSONObject(2).getInt("episode")==10 && episodes.getJSONObject(3).getInt("season")==2,"Natural episode ordering; filename overrides source episode marker");
            JSONObject first=episodes.getJSONObject(0);String videoId=first.getString("id");
            JSONObject stream=json("stream/series/"+StremioAddon.encode(videoId)+".json").getJSONArray("streams").getJSONObject(0);
            String video=stream.getString("url");
            check(video.startsWith("http://127.0.0.1:"+port+"/stremio/") && !video.contains("attacker.example"),"Stream URL uses local socket address, ignores hostile Host header");
            check(first.getJSONArray("streams").getJSONObject(0).getString("url").equals(video),"Metadata embeds same local stream; no external metadata needed");
            check(meta.getString("poster").startsWith("http://127.0.0.1:"+port+"/stremio/") && !meta.toString().contains("torbox.app"),"Artwork local and remote source URLs absent");
            check(json("stream/series/carstream%3Avideo%3A22%3A99.json").getJSONArray("streams").length()==0,"Unready videos have no stream");
            check(json("stream/movie/"+StremioAddon.encode(videoId)+".json").getJSONArray("streams").length()==0,"Wrong media type has no stream");
            check(json("meta/series/unknown.json").isNull("meta"),"Unknown metadata gives empty response");
            Response full=request("GET",new URL(video).getFile(),"");
            check(full.status==200 && Arrays.equals(full.body,content),"Full video bytes relayed through actual production server");
            Response range=request("GET",new URL(video).getFile(),"Range: bytes=100-299\r\n");
            check(range.status==206 && range.headers.contains("Content-Range: bytes 100-299/8192") && Arrays.equals(range.body,Arrays.copyOfRange(content,100,300)),"Seeking preserves range, content range and exact bytes");
            Response mediaHead=request("HEAD",new URL(video).getFile(),"");
            check(mediaHead.status==200 && mediaHead.body.length==0 && mediaHead.headers.contains("Content-Length: 8192"),"Video HEAD forwards size without body");
            check(request("GET",route("play/carstream%3Avideo%3A22%3A99/test.mp4"),"").status==404,"Direct play rejects unready media");
            check(server.getActiveStreamCount()==0 && server.getTotalBytesRelayed()==8392,"Stream slots released and relayed bytes counted");
            check(request("GET","/api/library","").status==401,"Existing browser API still requires its pairing code");
            settings.regeneratePairingCode();
            check(!settings.getStremioToken().equals(token) && request("GET",route("manifest.json"),"").status==401 && request("GET",new URL(video).getFile(),"").status==401,"Pairing reset revokes old catalog and video links");
            Path results=Paths.get(args.length>0?args[0]:"stremio-results.json");
            Files.writeString(results,new JSONObject().put("kind","Real JVM HTTP/relay; fixture library/upstream and Android preferences; no Android UI, codec, cellular or Stremio app execution").put("checks",new JSONArray(checks)).toString(2));
            System.out.println("PASS: "+checks.size()+" Stremio HTTP and relay checks");
        } finally {server.close();upstream.stop(0);}
    }
}

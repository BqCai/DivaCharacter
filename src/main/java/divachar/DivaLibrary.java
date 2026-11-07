package divachar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Index and extract selected CPK resources off the game and render threads. */
final class DivaLibrary {
    record Module(String id,String name){}
    record Pv(String id,String name,boolean available){}
    private record Catalog(Path root,Path cache,List<Module> modules,List<Pv> pvs){}
    private static final String[] INPUTS={"diva_main.cpk","diva_main_region.cpk","diva_dlc00.cpk","diva_dlc00_region.cpk","MikuMikuModel/MikuMikuLibrary.dll"};
    private final Path root;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(job->{Thread thread=new Thread(job,"DivaCharacter-import");thread.setDaemon(true);return thread;});
    private volatile Catalog catalog;
    private final AtomicBoolean choosing=new AtomicBoolean();
    private volatile String message="Choose the MEGA39's+ installation directory.";
    private long revision;

    DivaLibrary(Path root){this.root=root.toAbsolutePath().normalize();}

    void start(){
        Path settings=root.resolve("source.json");
        if(Files.isRegularFile(settings)){
            try{setPath(new JSONObject(Files.readString(settings)).getString("gamePath"));}
            catch(Exception error){message="Cannot read the saved game directory: "+error.getMessage();}
        }
    }

    String path(){Catalog current=catalog;return current==null?"":current.root.toString();}
    List<Module> modules(){Catalog current=catalog;return current==null?List.of():current.modules;}
    List<Pv> pvs(){Catalog current=catalog;return current==null?List.of():current.pvs;}
    String message(){return message;}

    void browse(){
        if(!choosing.compareAndSet(false,true))return;
        Thread picker=new Thread(()->{
            try{
                Files.createDirectories(root);
                Path temp=Files.createTempDirectory(root,"choose-folder-");
                try{
                    launch(List.of("browse",temp.toString(),path()),temp,300);
                    Path selected=temp.resolve("selected.txt");
                    if(Files.isRegularFile(selected))setPath(Files.readString(selected));
                }finally{deleteTemporary(temp);}
            }catch(Exception error){message="Folder picker failed. Paste the game path to scan it.";}
            finally{choosing.set(false);}
        },"DivaCharacter-folder-picker");
        picker.setDaemon(true);
        picker.start();
    }

    synchronized void setPath(String input){
        long request=++revision;
        message="Reading the original outfit tables…";
        worker.execute(()->{
            try{
                Catalog next=readCatalog(input);
                synchronized(this){
                    if(request!=revision)return;
                    writeSettings(next.root);
                    catalog=next;
                    message=next.modules.size()+" Miku outfits and "+next.pvs.size()+" PVs indexed.";
                }
            }catch(Exception error){synchronized(this){if(request==revision)message="Could not index MEGA39's+: "+error.getMessage();}}
        });
    }

    Future<Assets> prepare(String id){
        return worker.submit(()->{
            boolean local=!Assets.hasReference();
            if(id.equals("miku_default")&&!local)return new Assets();
            String outfit=id.equals("miku_default")?"0":id;
            Catalog current=catalog;
            if(current==null)throw new IOException("Set a valid MEGA39's+ directory first.");
            if(!current.modules.stream().anyMatch(module->module.id.equals(outfit)))throw new IOException("Unknown outfit "+outfit);
            Path shared=local?prepareShared(current):null;
            JSONArray reference=null;
            if(local&&!outfit.equals("0")){
                Path base=prepareModule(current,"0",shared,null);
                reference=new JSONObject(Files.readString(base.resolve("module.json"))).getJSONArray("bones");
            }
            return new Assets(prepareModule(current,outfit,shared,reference),shared,reference);
        });
    }

    private Path prepareModule(Catalog current,String id,Path shared,JSONArray reference)throws Exception{
        Path target=current.cache.resolve("module-"+id);
        if(Files.isRegularFile(target.resolve("module.json")))return target;
        Path temporary=Files.createTempDirectory(current.cache,"building-"+id+"-");
        try{
            run("module",current.root,temporary,id);
            new Assets(temporary,shared,reference);
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
            catch(FileAlreadyExistsException concurrent){if(!Files.isRegularFile(target.resolve("module.json")))throw concurrent;}
            return target;
        }finally{deleteTemporary(temporary);}
    }

    private Path prepareShared(Catalog current)throws Exception{
        Path shared=current.cache.resolve("shared");
        if(Files.isRegularFile(shared.resolve("faces.json"))&&Files.isRegularFile(shared.resolve("gameplay.bin")))return shared;
        Path temporary=Files.createTempDirectory(current.cache,"building-shared-");
        try{
            run("faces",current.root,temporary,null);
            new JSONObject(Files.readString(temporary.resolve("faces.json"))).getJSONArray("meshes");
            new GameplayPerformance.Library(Files.readAllBytes(temporary.resolve("gameplay.bin")));
            Files.move(temporary,shared,StandardCopyOption.ATOMIC_MOVE);
            return shared;
        }finally{deleteTemporary(temporary);}
    }

    Future<PvClip> preparePv(String id){
        Catalog current=catalog;
        return worker.submit(()->{
            if(current==null)throw new IOException("Scan a MEGA39's+ installation first.");
            if(current.pvs.stream().noneMatch(pv->pv.id.equals(id)&&pv.available))
                throw new IOException("This PV has no supported solo Miku 3D preview.");
            Path target=current.cache.resolve("pv-"+id);
            if(Files.isRegularFile(target.resolve("preview.bin")))return new PvClip(target);
            Path temporary=Files.createTempDirectory(current.cache,"building-pv-"+id+"-");
            try{
                run("pv",current.root,temporary,id);
                new PvClip(temporary);
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
                return new PvClip(target);
            }finally{deleteTemporary(temporary);}
        });
    }

    private Catalog readCatalog(String input)throws Exception{
        String cleaned=input.strip();
        if(cleaned.length()>1&&cleaned.startsWith("\"")&&cleaned.endsWith("\""))cleaned=cleaned.substring(1,cleaned.length()-1);
        if(cleaned.isBlank())throw new IOException("Enter the game directory or the path to DivaMegaMix.exe.");
        Path game=Path.of(cleaned).toRealPath();
        if(Files.isRegularFile(game)&&game.getFileName().toString().equalsIgnoreCase("DivaMegaMix.exe"))game=game.getParent();
        if(!Files.isRegularFile(game.resolve("DivaMegaMix.exe")))throw new IOException("DivaMegaMix.exe was not found.");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(Assets.bytes("tools/importer.version"));
        digest.update(game.toString().getBytes(StandardCharsets.UTF_8));
        for(String filename:INPUTS){
            Path file=game.resolve(filename);
            if(!Files.isRegularFile(file)){
                if(filename.startsWith("diva_dlc"))continue;
                throw new IOException(filename+" was not found.");
            }
            digest.update((filename+"\n"+Files.size(file)+"\n"+Files.getLastModifiedTime(file)).getBytes(StandardCharsets.UTF_8));
        }
        Path cache=root.resolve("cache").resolve(HexFormat.of().formatHex(digest.digest()));
        Files.createDirectories(cache);
        Path catalogFile=cache.resolve("catalog.json");
        Path pvFile=cache.resolve("pv-catalog.json");
        if(!Files.isRegularFile(catalogFile)||!Files.isRegularFile(pvFile)){
            Path temporary=Files.createTempDirectory(cache,"catalog-");
            try{
                run("catalog",game,temporary,null);
                Files.move(temporary.resolve("pv-catalog.json"),pvFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary.resolve("catalog.json"),catalogFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            finally{deleteTemporary(temporary);}
        }
        List<Module> modules=new ArrayList<>();
        for(Object value:new JSONArray(Files.readString(catalogFile))){
            JSONObject row=(JSONObject)value;
            String id=row.getString("id");
            if(!id.matches("[0-9]{1,9}"))throw new IOException("Invalid module ID in catalogue.");
            modules.add(new Module(id,row.getString("name")));
        }
        List<Pv> pvs=new ArrayList<>();
        for(Object value:new JSONArray(Files.readString(pvFile))){
            JSONObject row=(JSONObject)value;
            String id=row.getString("id");
            if(!id.matches("[0-9]{3,9}"))throw new IOException("Invalid PV ID in catalogue.");
            pvs.add(new Pv(id,row.getString("name"),row.getBoolean("available")));
        }
        return new Catalog(game,cache,List.copyOf(modules),List.copyOf(pvs));
    }

    private void writeSettings(Path path)throws IOException{
        Files.createDirectories(root);
        Path temp=Files.createTempFile(root,"source-",".json");
        try{
            Files.writeString(temp,new JSONObject().put("gamePath",path.toString()).toString(2));
            Files.move(temp,root.resolve("source.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }

    private void run(String operation,Path game,Path output,String id)throws Exception{
        List<String> arguments=new ArrayList<>(List.of(operation,game.toString(),output.toString()));
        if(id!=null)arguments.add(id);
        launch(arguments,output,90);
    }

    private synchronized Path executable()throws Exception{
        byte[] binary=Assets.bytes("tools/DivaImport.exe");
        byte[] config=Assets.bytes("tools/DivaImport.exe.config");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(binary);
        String signature=HexFormat.of().formatHex(digest.digest(config));
        Path executable=root.resolve("DivaImport-"+signature+".exe");
        installHelperFile(executable,binary);
        installHelperFile(executable.resolveSibling(executable.getFileName()+".config"),config);
        return executable;
    }

    private void installHelperFile(Path target,byte[] contents)throws IOException{
        if(!Files.isRegularFile(target)||!Arrays.equals(contents,Files.readAllBytes(target))){
            Path temporary=Files.createTempFile(root,"helper-",".tmp");
            try{Files.write(temporary,contents);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            finally{Files.deleteIfExists(temporary);}
        }
    }

    private void launch(List<String> arguments,Path output,int timeout)throws Exception{
        List<String> command=new ArrayList<>();
        command.add(executable().toString());command.addAll(arguments);
        Path log=output.resolve("import.log");
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            if(!process.waitFor(timeout,TimeUnit.SECONDS))throw new IOException("The helper timed out.");
            if(process.exitValue()!=0)throw new IOException(Files.readString(log).strip());
        }finally{
            if(process.isAlive()){process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);}
        }
    }

    private static void deleteTemporary(Path directory)throws IOException{
        if(!Files.isDirectory(directory))return;
        try(var files=Files.walk(directory)){
            for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
    }
}

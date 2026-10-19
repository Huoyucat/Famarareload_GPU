package com.wenkrang.famara.render.lib;

import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.platform.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.map.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** 旧图保留原Map ID，数字PNG只扫描一次；V2必须有完整收据，不加载pending或sidecar。 */
public final class LoadPhoto {
    private static final java.util.concurrent.atomic.AtomicLong epoch=new java.util.concurrent.atomic.AtomicLong();
    private static final Set<DirectoryStream<Path>> open=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static void close(){epoch.incrementAndGet();for(var stream:open)try{stream.close();}catch(Exception ignored){}open.clear();installed.clear();}
    private static final Map<Integer,MapView> installed=Collections.synchronizedMap(new WeakHashMap<>());
    private LoadPhoto() {}
    private static final class LegacyRenderer extends MapRenderer {
        private final BufferedImage image;
        private final Map<MapCanvas,Boolean> drawn=Collections.synchronizedMap(new WeakHashMap<>());
        LegacyRenderer(BufferedImage image){this.image=image;}
        @Override public void render(MapView view,MapCanvas canvas,Player player) {
            if(drawn.putIfAbsent(canvas,true)==null)canvas.drawImage(0,0,image);
        }
    }
    public static void loadPhoto(File dataFolder) {
        long ticket=epoch.incrementAndGet();AtomicBoolean awaiting=new AtomicBoolean();Path root=dataFolder.toPath().resolve("pictures");
        Sched.async(()->{
            try {
                if(ticket!=epoch.get() || !Files.isDirectory(root))return;
                DirectoryStream<Path> directory=Files.newDirectoryStream(root);open.add(directory);Iterator<Path> files=directory.iterator();DiskPhotoStore v2=new DiskPhotoStore(root);
                AtomicReference<Sched.Task> timer=new AtomicReference<>();AtomicBoolean finished=new AtomicBoolean();
                timer.set(Sched.asyncTimer(()->{
                    if(ticket!=epoch.get()){try{directory.close();}catch(Exception ignored){}open.remove(directory);finished.set(true);}
                    if(finished.get()){if(timer.get()!=null)timer.get().cancel();return;}
                    if(awaiting.get())return;
                    // 每次仅一个文件；不把全部历史图片和global安装任务同时堆到内存里。
                    try {
                        if(!files.hasNext()){directory.close();open.remove(directory);finished.set(true);if(timer.get()!=null)timer.get().cancel();return;}
                        Path file=files.next();String name=file.getFileName().toString();
                        if(!name.matches("[0-9]+[.]png") || !Files.isRegularFile(file))return;
                        int id=Integer.parseInt(name.substring(0,name.length()-4));
                        if(v2.isPending(id))return;
                        ImmutablePhoto photo=null;BufferedImage image=null;
                        if(Files.exists(root.resolve("metadata-v2").resolve(id+".json")))photo=v2.loadCommitted(id).orElse(null);
                        else image=legacy(file);
                        if(photo==null && image==null)return;
                        ImmutablePhoto frozen=photo;BufferedImage legacy=image;awaiting.set(true);
                        Sched.global(()->{
                            try {
                                if(ticket!=epoch.get())return;
                                MapView view=Bukkit.getMap(id);if(view==null || installed.get(id)==view)return;
                                if(frozen!=null)PhotoRuntime.install(view,frozen,Math.max(1,Bukkit.getMaxPlayers()));
                                else {
                                    for(MapRenderer renderer:view.getRenderers())if(renderer.getClass().getName().startsWith("com.wenkrang.famara."))view.removeRenderer(renderer);
                                    view.setLocked(true);view.addRenderer(new LegacyRenderer(legacy));
                                }
                                installed.put(id,view);
                            } finally{awaiting.set(false);}
                        });
                    } catch(Exception error){awaiting.set(false);Logger.getLogger("FamaraV2").warning("历史照片未加载，原文件保留："+error);}
                },0,1));
            } catch(Exception error){Logger.getLogger("FamaraV2").warning("历史照片扫描失败："+error);}
        });
    }
    private static BufferedImage legacy(Path file)throws Exception {
        if(Files.size(file)>2*1024*1024)throw new IllegalArgumentException("历史PNG过大");
        try(ImageInputStream input=ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("历史PNG格式无效");
            ImageReader reader=readers.next();
            try{reader.setInput(input,true,true);if(!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0)!=128 || reader.getHeight(0)!=128)throw new IllegalArgumentException("历史照片不是128×128 PNG");return reader.read(0);}
            finally{reader.dispose();}
        }
    }
}

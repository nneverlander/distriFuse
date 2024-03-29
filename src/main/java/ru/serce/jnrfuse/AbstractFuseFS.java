package ru.serce.jnrfuse;

import jnr.ffi.*;
import jnr.ffi.Runtime;
import jnr.ffi.mapper.FromNativeConverter;
import jnr.ffi.provider.jffi.ClosureHelper;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.Flock;
import ru.serce.jnrfuse.struct.FuseBufvec;
import ru.serce.jnrfuse.struct.FuseFileInfo;
import ru.serce.jnrfuse.struct.FuseOperations;
import ru.serce.jnrfuse.struct.FusePollhandle;
import ru.serce.jnrfuse.struct.Statvfs;
import ru.serce.jnrfuse.struct.Timespec;
import ru.serce.jnrfuse.utils.SecurityUtils;

import java.io.IOException;
import java.lang.String;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Created by serce on 27.05.15.
 */
public abstract class AbstractFuseFS implements FuseFS {

    private static final int TIMEOUT = 2000; // seconds
    private Set<String> notImplementedMethods;
    protected final LibFuse libFuse;
    protected final FuseOperations fuseOperations;
    protected final AtomicBoolean mounted = new AtomicBoolean();
    protected Path mountPoint;

    public AbstractFuseFS() {
        LibraryLoader<LibFuse> loader = LibraryLoader.create(LibFuse.class);
        libFuse = loader.load("libfuse.so.2");

        Runtime runtime = Runtime.getSystemRuntime();
        fuseOperations = new FuseOperations(runtime);
        init(fuseOperations);
    }

    private void init(FuseOperations fuseOperations) {
        notImplementedMethods = Arrays.stream(getClass().getMethods())
                .filter(method -> method.getAnnotation(NotImplemented.class) != null)
                .map(Method::getName)
                .collect(Collectors.toSet());

        AbstractFuseFS fuse = this;
        if (isImplemented("getattr")) {
            fuseOperations.getattr.set((path, stbuf) -> fuse.getattr(path, FileStat.of(stbuf)));
        }
        if (isImplemented("readlink")) {
            fuseOperations.readlink.set(fuse::readlink);
        }
        if (isImplemented("mknod")) {
            fuseOperations.mknod.set(fuse::mknod);
        }
        if (isImplemented("mkdir")) {
            fuseOperations.mkdir.set(fuse::mkdir);
        }
        if (isImplemented("unlink")) {
            fuseOperations.unlink.set(fuse::unlink);
        }
        if (isImplemented("rmdir")) {
            fuseOperations.rmdir.set(fuse::rmdir);
        }
        if (isImplemented("symlink")) {
            fuseOperations.symlink.set(fuse::symlink);
        }
        if (isImplemented("rename")) {
            fuseOperations.rename.set(fuse::rename);
        }
        if (isImplemented("link")) {
            fuseOperations.link.set(fuse::link);
        }
        if (isImplemented("chmod")) {
            fuseOperations.chmod.set(fuse::chmod);
        }
        if (isImplemented("chown")) {
            fuseOperations.chown.set(fuse::chown);
        }
        if (isImplemented("truncate")) {
            fuseOperations.truncate.set(fuse::truncate);
        }
        if (isImplemented("open")) {
            fuseOperations.open.set((path, fi) -> fuse.open(path, FuseFileInfo.of(fi)));
        }
        if (isImplemented("read")) {
            fuseOperations.read.set((path, buf, size, offset, fi) -> fuse.read(path, buf, size, offset, FuseFileInfo.of(fi)));
        }
        if (isImplemented("write")) {
            fuseOperations.write.set((path, buf, size, offset, fi) -> fuse.write(path, buf, size, offset, FuseFileInfo.of(fi)));
        }
        if (isImplemented("statfs")) {
            fuseOperations.statfs.set((path, stbuf) -> fuse.statfs(path, Statvfs.of(stbuf)));
        }
        if (isImplemented("flush")) {
            fuseOperations.flush.set((path, fi) -> fuse.flush(path, FuseFileInfo.of(fi)));
        }
        if (isImplemented("release")) {
            fuseOperations.release.set((path, fi) -> fuse.release(path, FuseFileInfo.of(fi)));
        }
        if (isImplemented("fsync")) {
            fuseOperations.fsync.set((path, isdatasync, fi) -> fuse.fsync(path, isdatasync, FuseFileInfo.of(fi)));
        }
        if (isImplemented("setxattr")) {
            fuseOperations.setxattr.set(fuse::setxattr);
        }
        if (isImplemented("getxattr")) {
            fuseOperations.getxattr.set(fuse::getxattr);
        }
        if (isImplemented("listxattr")) {
            fuseOperations.listxattr.set(fuse::listxattr);
        }
        if (isImplemented("removexattr")) {
            fuseOperations.removexattr.set(fuse::removexattr);
        }
        if (isImplemented("opendir")) {
            fuseOperations.opendir.set((path, fi) -> fuse.opendir(path, FuseFileInfo.of(fi)));
        }
        if (isImplemented("readdir")) {
            fuseOperations.readdir.set((path, buf, filter, offset, fi) -> {
                ClosureHelper helper = ClosureHelper.getInstance();
                FromNativeConverter<FuseFillDir, Pointer> conveter = helper.getNativeConveter(FuseFillDir.class);
                FuseFillDir filterFunc = conveter.fromNative(filter, helper.getFromNativeContext());
                return fuse.readdir(path, buf, filterFunc, offset, FuseFileInfo.of(fi));
            });
        }
        if (isImplemented("releasedir")) {
            fuseOperations.releasedir.set((path, fi) -> fuse.releasedir(path, FuseFileInfo.of(fi)));
        }
        if (isImplemented("fsyncdir")) {
            fuseOperations.fsyncdir.set((path, fi) -> fuse.fsyncdir(path, FuseFileInfo.of(fi)));
        }
        if (isImplemented("init")) {
            fuseOperations.init.set(fuse::init);
        }
        if (isImplemented("destroy")) {
            fuseOperations.destroy.set(fuse::destroy);
        }
        if (isImplemented("access")) {
            fuseOperations.access.set(fuse::access);
        }
        if (isImplemented("create")) {
            fuseOperations.create.set((path, mode, fi) -> fuse.create(path, mode, FuseFileInfo.of(fi)));
        }
        if (isImplemented("ftruncate")) {
            fuseOperations.ftruncate.set((path, size, fi) -> fuse.ftruncate(path, size, FuseFileInfo.of(fi)));
        }
        if (isImplemented("fgetattr")) {
            fuseOperations.fgetattr.set((path, stbuf, fi) -> fuse.fgetattr(path, FileStat.of(stbuf), FuseFileInfo.of(fi)));
        }
        if (isImplemented("lock")) {
            fuseOperations.lock.set((path, fi, cmd, flock) -> fuse.lock(path, FuseFileInfo.of(fi), cmd, Flock.of(flock)));
        }
        if (isImplemented("utimens")) {
            fuseOperations.utimens.set((path, timespec) -> {
                Timespec timespec1 = Timespec.of(timespec);
                Timespec timespec2 = Timespec.of(timespec.getPointer(Struct.size(timespec1)));
                fuse.utimens(path, new Timespec[]{timespec1, timespec2});
            });
        }
        if (isImplemented("bmap")) {
            fuseOperations.bmap.set((path, blocksize, idx) -> fuse.bmap(path, blocksize, idx.getLong(0)));
        }
        if (isImplemented("ioctl")) {
            fuseOperations.ioctl.set((path, cmd, arg, fi, flags, data) -> fuse.ioctl(path, cmd, arg, FuseFileInfo.of(fi), flags, data));
        }
        if (isImplemented("poll")) {
            fuseOperations.poll.set((path, fi, ph, reventsp) -> fuse.poll(path, FuseFileInfo.of(fi), FusePollhandle.of(ph), reventsp));
        }
        if (isImplemented("write_buf")) {
            fuseOperations.write_buf.set((path, buf, off, fi) -> fuse.write_buf(path, FuseBufvec.of(buf), off, FuseFileInfo.of(fi)));
        }
        if (isImplemented("read_buf")) {
            fuseOperations.read_buf.set((path, bufp, size, off, fi) -> fuse.read_buf(path, bufp, size, off, FuseFileInfo.of(fi)));
        }
        if (isImplemented("flock")) {
            fuseOperations.flock.set((path, fi, op) -> fuse.flock(path, FuseFileInfo.of(fi), op));
        }
        if (isImplemented("fallocate")) {
            fuseOperations.fallocate.set((path, mode, off, length, fi) -> fuse.fallocate(path, mode, off, length, FuseFileInfo.of(fi)));
        }
    }

    @Override
    public void mount(Path mountPoint, boolean blocking, boolean debug) {
        if (!mounted.compareAndSet(false, true)) {
            throw new FuseException("Fuse fs already mounted!");
        }
        this.mountPoint = mountPoint;
        String[] arg;
        if (!debug) {
            arg = new String[]{getFSName(), "-f", mountPoint.toAbsolutePath().toString()};
        } else {
            arg = new String[]{getFSName(), "-f", "-d", mountPoint.toAbsolutePath().toString()};
        }
        try {
            if (!Files.isDirectory(mountPoint)) {
                throw new FuseException("Mount point should be directory");
            }
            if (SecurityUtils.canHandleShutdownHooks()) {
                java.lang.Runtime.getRuntime().addShutdownHook(new Thread(this::umount));
            }
            int res;
            if (blocking) {
                res = execMount(arg);
            } else {
                try {
                    res = CompletableFuture
                            .supplyAsync(() -> execMount(arg))
                            .get(TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // ok
                    res = 0;
                }
            }
            if (res != 0) {
                throw new FuseException("Unable to mount FS, return code = " + res);
            }
        } catch (Exception e) {
            mounted.set(false);
            throw new FuseException("Unable to mount FS", e);
        }
    }

    private boolean isImplemented(String funcName) {
        return !notImplementedMethods.contains(funcName);
    }

    private int execMount(String[] arg) {
        return libFuse.fuse_main_real(arg.length, arg, fuseOperations, Struct.size(fuseOperations), null);
    }

    @Override
    public void umount() {
        if (!mounted.get()) {
            return;
        }
        try {
            new ProcessBuilder("fusermount", "-u", "-z", mountPoint.toAbsolutePath().toString()).start();
            mounted.set(false);
        } catch (IOException e) {
            throw new FuseException("Unable to umount FS", e);
        }
    }

    protected String getFSName() {
        return "fusefs" + ThreadLocalRandom.current().nextInt();
    }
}

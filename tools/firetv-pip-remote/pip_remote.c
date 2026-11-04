#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/file.h>

#define NETFLIX_KEY 744
#define REMOTE_NAME "Amazon Fire TV Remote"
#define VIRTUAL_NAME "NannyCam PiP Forwarder"
#define NANNY_PACKAGE "com.cisiur.nannycamviewer/"
static volatile sig_atomic_t running=1;
static void on_signal(int n) { (void)n; running=0; }
static long long now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (long long)t.tv_sec*1000+t.tv_nsec/1000000; }
static void logline(const char *msg) { fprintf(stderr,"[pip-remote] %s\n",msg); fflush(stderr); }
static bool bit(const unsigned long *p,int k) { return (p[k/(8*sizeof(unsigned long))]>>(k%(8*sizeof(unsigned long))))&1UL; }
static int find_remote(char *path,size_t cap) {
  for(int i=0;i<32;i++) {
    char p[80], name[256]={0}; snprintf(p,sizeof p,"/dev/input/event%d",i);
    int fd=open(p,O_RDONLY|O_NONBLOCK|O_CLOEXEC); if(fd<0) continue;
    int ok=ioctl(fd,EVIOCGNAME(sizeof name),name);
    if(ok>=0 && strcmp(name,REMOTE_NAME)==0){ snprintf(path,cap,"%s",p); return fd; }
    close(fd);
  }
  return -1;
}
static int make_virtual(int source_fd) {
  int out=open("/dev/uinput",O_WRONLY|O_NONBLOCK|O_CLOEXEC);
  if(out<0){ perror("open /dev/uinput"); return -1; }
  unsigned long eventbits[(EV_MAX+8*sizeof(long))/(8*sizeof(long))]={0};
  if(ioctl(source_fd,EVIOCGBIT(0,sizeof(eventbits)),eventbits)<0) {
    perror("EVIOCGBIT(event types)"); close(out); return -1;
  }
  const char *operation="unknown";
  for(int type=0;type<=EV_MAX;type++) {
    if(!bit(eventbits,type) || type==EV_SYN) continue;
    // EV_REP is repeat configuration, not a key press.
    if(type==EV_REP) continue;
    if(type!=EV_KEY && type!=EV_MSC && type!=EV_REL && type!=EV_ABS) {
      fprintf(stderr,"Unsupported event type %d; refusing to grab\n",type);
      errno=EOPNOTSUPP; operation="event capability check"; goto fail;
    }
    operation="UI_SET_EVBIT";
    if(ioctl(out,UI_SET_EVBIT,type)<0) goto fail;
    int maxcode=0;
    unsigned long codes[(KEY_MAX+8*sizeof(long))/(8*sizeof(long))]={0};
    if(type==EV_KEY) maxcode=KEY_MAX;
    else if(type==EV_MSC) maxcode=MSC_MAX;
    else if(type==EV_REL) maxcode=REL_MAX;
    else if(type==EV_ABS) maxcode=ABS_MAX;
    else { fprintf(stderr,"Unsupported event type %d; refusing to grab\n",type); goto fail; }
    operation="EVIOCGBIT(codes)";
    if(ioctl(source_fd,EVIOCGBIT(type,sizeof(codes)),codes)<0) goto fail;
    for(int code=0;code<=maxcode;code++) if(bit(codes,code)) {
      int req=(type==EV_KEY)?UI_SET_KEYBIT:(type==EV_MSC)?UI_SET_MSCBIT:(type==EV_REL)?UI_SET_RELBIT:UI_SET_ABSBIT;
      operation="UI_SET_*BIT";
      if(ioctl(out,req,code)<0) { fprintf(stderr,"Failed to enable event type=%d code=%d\n",type,code); goto fail; }
    }
    if(type==EV_ABS) { logline("ABS axes found; prototype refuses to grab this device"); goto fail; }
  }
  // Preserve the physical remote IDs so Fire OS applies its SETTINGS/APP_1 map.
  struct input_id physical_id={0};
  operation="EVIOCGID";
  if(ioctl(source_fd,EVIOCGID,&physical_id)<0) goto fail;
  struct uinput_setup setup={0};
  setup.id=physical_id;
  fprintf(stderr,"[pip-remote] Physical input ID: bus=%04x vendor=%04x product=%04x version=%04x\n",
    setup.id.bustype,setup.id.vendor,setup.id.product,setup.id.version);
  fflush(stderr);
  snprintf(setup.name,UINPUT_MAX_NAME_SIZE,"%s",VIRTUAL_NAME);
  operation="UI_DEV_SETUP";
  if(ioctl(out,UI_DEV_SETUP,&setup)<0) goto fail;
  operation="UI_DEV_CREATE";
  if(ioctl(out,UI_DEV_CREATE)<0) goto fail;
  usleep(250000);
  return out;
fail:
  fprintf(stderr,"[pip-remote] %s failed: %s (errno=%d)\n",operation,strerror(errno),errno);
  close(out); return -1;
}
typedef struct {
  int stack_id;
  bool found;
  bool pinned;
  bool visible;
} nanny_stack_state;

static nanny_stack_state read_nanny_stack(void) {
  nanny_stack_state result={.stack_id=-1};
  FILE *p=popen("/system/bin/cmd activity stack list 2>/dev/null","r");
  if(!p) { perror("stack list"); return result; }
  char line[8192];
  int current_id=-1;
  bool current_pinned=false;
  bool found=false;
  while(fgets(line,sizeof(line),p)) {
    int id;
    if(sscanf(line,"Stack id=%d",&id)==1) {
      current_id=id;
      current_pinned=false;
      continue;
    }
    if(current_id<0) continue;
    if(strstr(line,"mWindowingMode=pinned")) current_pinned=true;
    if(!found && strstr(line,NANNY_PACKAGE)) {
      result.stack_id=current_id;
      result.found=true;
      result.pinned=current_pinned;
      result.visible=strstr(line,"visible=true")!=NULL;
      found=true;
    }
  }
  int status=pclose(p);
  if(status!=0) {
    fprintf(stderr,"[pip-remote] stack list failed, status=%d\n",status);
    result=(nanny_stack_state){.stack_id=-1};
  } else if(!found) {
    logline("NannyCam task not present in stack list");
  }
  return result;
}
static void log_stack_state(const char *phase,nanny_stack_state state) {
  fprintf(stderr,"[pip-remote] %s: stack=%d found=%d pinned=%d visible=%d\n",
    phase,state.stack_id,state.found,state.pinned,state.visible);
  fflush(stderr);
}
static bool confirmed_pip(void) {
  nanny_stack_state first=read_nanny_stack();
  log_stack_state("State check",first);
  if(!first.found || !first.pinned || !first.visible) return false;
  usleep(500000);
  nanny_stack_state second=read_nanny_stack();
  log_stack_state("Stability check",second);
  return second.found && second.pinned && second.visible &&
    second.stack_id==first.stack_id;
}
static void restore_pip(void) {
  nanny_stack_state initial=read_nanny_stack();
  log_stack_state("Initial",initial);
  if(!initial.found) { logline("NannyCam task not found; no action"); return; }
  bool ready=confirmed_pip();
  const int max_attempts=4;
  for(int attempt=1;attempt<=max_attempts && running && !ready;attempt++) {
    nanny_stack_state current=read_nanny_stack();
    if(!current.found) { logline("NannyCam task disappeared; stopping"); return; }
    if(current.pinned && current.visible) {
      ready=confirmed_pip();
      if(ready) break;
    }
    char command[256];
    snprintf(command,sizeof(command),
      "/system/bin/cmd activity stack move-top-activity-to-pinned-stack %d 1424 54 1808 270",
      current.stack_id);
    fprintf(stderr,"[pip-remote] PiP attempt %d/%d, stack %d\n",
      attempt,max_attempts,current.stack_id);
    fflush(stderr);
    int status=system(command);
    fprintf(stderr,"[pip-remote] PiP command status=%d\n",status);
    fflush(stderr);
    usleep(750000);
    ready=confirmed_pip();
    if(!ready && attempt<max_attempts) usleep(450000);
  }
  if(!running) return;
  if(!ready) {
    logline("PiP not confirmed after 4 attempts; skipping camera recovery");
    return;
  }
  logline("PiP confirmed pinned+visible in two consecutive checks");
  logline("Broadcasting RESTORE_CAMERA to existing NannyCam activity");
  int status=system("/system/bin/am broadcast "
    "-a com.cisiur.nannycamviewer.action.RESTORE_CAMERA "
    "-n com.cisiur.nannycamviewer/.ui.CameraRecoveryReceiver");
  fprintf(stderr,"[pip-remote] Recovery broadcast status=%d\n",status);
  fflush(stderr);
}
static pid_t start_restore_worker(int input_fd,int output_fd) {
  pid_t child=fork();
  if(child==0) {
    close(input_fd);
    close(output_fd);
    signal(SIGINT,on_signal);
    signal(SIGTERM,on_signal);
    restore_pip();
    _exit(0);
  }
  if(child<0) perror("[pip-remote] fork");
  return child;
}
static int acquire_single_instance(void) {
  int fd=open("/data/local/tmp/pip_remote.lock",O_CREAT|O_RDWR|O_CLOEXEC,0600);
  if(fd<0) { perror("open instance lock"); return -1; }
  if(flock(fd,LOCK_EX|LOCK_NB)<0) {
    if(errno==EWOULDBLOCK || errno==EAGAIN)
      logline("Another pip_remote instance already owns the lock; exiting");
    else perror("flock");
    close(fd); return -1;
  }
  if(ftruncate(fd,0)==0) {
    char pid[32]; int len=snprintf(pid,sizeof(pid),"%ld\n",(long)getpid());
    if(len>0) (void)write(fd,pid,(size_t)len);
  }
  return fd;
}
static void cap_redirected_log(void) {
  struct stat s;
  if(fstat(STDERR_FILENO,&s)==0 && S_ISREG(s.st_mode) && s.st_size>512*1024) {
    if(ftruncate(STDERR_FILENO,0)==0) {
      (void)lseek(STDERR_FILENO,0,SEEK_SET);
      (void)lseek(STDOUT_FILENO,0,SEEK_SET);
      logline("Log size cap reached; truncated previous output");
    }
  }
}
static void stop_worker(pid_t *worker) {
  if(*worker>0) {
    kill(*worker,SIGTERM);
    for(int i=0;i<15;i++) {
      pid_t done=waitpid(*worker,NULL,WNOHANG);
      if(done==*worker || (done<0 && errno==ECHILD)) { *worker=-1; return; }
      usleep(100000);
    }
    kill(*worker,SIGKILL);
    (void)waitpid(*worker,NULL,0);
    *worker=-1;
  }
}
static void release_remote(int *in,int *out,pid_t *worker) {
  stop_worker(worker);
  if(*in>=0) (void)ioctl(*in,EVIOCGRAB,0);
  if(*out>=0) {
    (void)ioctl(*out,UI_DEV_DESTROY);
    close(*out);
  }
  if(*in>=0) close(*in);
  *in=-1; *out=-1;
}
int main(int argc,char **argv) {
  long seconds=0;
  if(argc>2) {
    fprintf(stderr,"Usage: %s [duration_seconds: 0..604800, 0=indefinite]\n",argv[0]);
    return 2;
  }
  if(argc==2) {
    char *end=NULL; errno=0;
    seconds=strtol(argv[1],&end,10);
    if(errno || !end || *end!='\0' || seconds<0 || seconds>604800) {
      fprintf(stderr,"Usage: %s [duration_seconds: 0..604800, 0=indefinite]\n",argv[0]);
      return 2;
    }
  }
  signal(SIGINT,on_signal); signal(SIGTERM,on_signal); signal(SIGHUP,SIG_IGN);
  int lock_fd=acquire_single_instance();
  if(lock_fd<0) return 6;
  long long until=seconds?now_ms()+(long long)seconds*1000:0;
  int in=-1,out=-1;
  pid_t worker=-1;
  long long last_press=0,last_finish=0,last_missing_notice=0;
  bool suppressed_frame=false;
  logline(seconds?"Starting bounded test mode":"Starting continuous mode (no timeout)");
  while(running && (!until || now_ms()<until)) {
    cap_redirected_log();
    if(in<0) {
      char path[80];
      int candidate=find_remote(path,sizeof(path));
      if(candidate<0) {
        if(now_ms()-last_missing_notice>30000 || last_missing_notice==0) {
          logline("Remote absent; waiting for Bluetooth reconnection");
          last_missing_notice=now_ms();
        }
        usleep(500000);
        continue;
      }
      fprintf(stderr,"[pip-remote] Remote: %s\n",path); fflush(stderr);
      int virtual_fd=make_virtual(candidate);
      if(virtual_fd<0) {
        logline("Virtual remote setup failed; physical remote NOT grabbed; retry in 3s");
        close(candidate); usleep(3000000); continue;
      }
      if(ioctl(candidate,EVIOCGRAB,1)<0) {
        perror("EVIOCGRAB");
        (void)ioctl(virtual_fd,UI_DEV_DESTROY);
        close(virtual_fd); close(candidate); usleep(3000000); continue;
      }
      in=candidate;out=virtual_fd;
      last_press=last_finish=0;
      suppressed_frame=false;
      logline("Remote grabbed; forwarding all keys except Netflix. Ctrl+C or SIGTERM releases it.");
      continue;
    }
    if(worker>0) {
      int status=0;
      pid_t done=waitpid(worker,&status,WNOHANG);
      if(done==worker || (done<0 && errno==ECHILD)) {
        worker=-1;
        last_finish=now_ms();
        logline("PiP restore worker finished");
      }
    }
    struct pollfd pfd={.fd=in,.events=POLLIN};
    int left=250;
    if(until) {
      long long remain=until-now_ms();
      if(remain<left) left=remain>0?(int)remain:0;
    }
    int r=poll(&pfd,1,left);
    if(r<0) { if(errno==EINTR)continue; perror("poll");break; }
    if(r==0)continue;
    if(pfd.revents&(POLLHUP|POLLERR|POLLNVAL)) {
      logline("Physical remote disconnected; releasing virtual remote and waiting for reconnect");
      release_remote(&in,&out,&worker);
      usleep(300000);
      continue;
    }
    struct input_event e;
    ssize_t n=read(in,&e,sizeof e);
    if(n!=sizeof e) {
      if(n<0 && (errno==EAGAIN || errno==EINTR)) continue;
      logline("Remote read ended; releasing and waiting for reconnect");
      release_remote(&in,&out,&worker);
      usleep(300000);
      continue;
    }
    if(e.type==EV_KEY && e.code==NETFLIX_KEY) {
      suppressed_frame=true;
      if(e.value==1) {
        long long now=now_ms();
        if(worker>0) logline("Netflix press ignored: restoration already running");
        else if(now-last_press>900 && (last_finish==0 || now-last_finish>1800)) {
          last_press=now;
          logline("Netflix key; starting PiP + camera recovery");
          worker=start_restore_worker(in,out);
        } else logline("Netflix press ignored: debounce/cooldown");
      }
      continue;
    }
    if(e.type==EV_SYN && suppressed_frame) { suppressed_frame=false; continue; }
    if(write(out,&e,sizeof e)!=sizeof e) {
      perror("uinput forward");
      logline("Virtual remote forwarding failed; releasing and retrying safely");
      release_remote(&in,&out,&worker);
      usleep(300000);
    }
  }
  release_remote(&in,&out,&worker);
  logline("Stopped. Original remote released.");
  (void)flock(lock_fd,LOCK_UN);close(lock_fd);
  return 0;
}

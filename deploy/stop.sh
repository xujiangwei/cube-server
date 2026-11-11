#!/bin/bash
# -----------------------------------------------------------------------------
# Stop Cube Server
# -----------------------------------------------------------------------------

# Better OS/400 detection
os400=false
darwin=false
case "`uname`" in
CYGWIN*) cygwin=true;;
OS400*) os400=true;;
Darwin*) darwin=true;;
esac

# resolve links - $0 may be a softlink
PRG="$0"

while [ -h "$PRG" ] ; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done

PRGDIR=`dirname "$PRG"`

STOP_STATUS=0
sh "$PRGDIR"/stop-ferryboat.sh "$@" || STOP_STATUS=1

sh "$PRGDIR"/stop-dispatcher.sh "$@" || STOP_STATUS=1

sh "$PRGDIR"/stop-service.sh "$@" || STOP_STATUS=1
exit "$STOP_STATUS"

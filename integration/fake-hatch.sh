#!/bin/sh
if [ "$1" = env ] && [ "$2" = show ]; then
  printf '%s\n' '{"default":{"type":"virtual","scripts":{"smoke":["echo smoke"],"start":["echo start"],"test":["echo test"]}}}'
elif [ "$1" = project ] && [ "$2" = metadata ]; then
  printf '%s\n' 'hatch-smoke'
else
  printf '%s|%s|%s\n' "$PWD" "$1" "$2" >> /tmp/hatch-ui-test/command-ran.txt
fi

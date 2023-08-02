set -x
APP_NAME=centraldogma
APP_MAIN=com.linecorp.centraldogma.server.Main

[[ `hostname` =~ -([0-9]+)$ ]] || exit 1
ordinal=${BASH_REMATCH[1]}
config_file="/opt/centraldogma/conf/dogma-replica.json"
sed -i 's#${MACHINE_INDEX}#'"\"1$ordinal\""'#g' "$config_file"
server=""
for ((i=0;i<3;i++))
do
  conf="""\"1$i\": {\"host\": \"config-server-$i.config-server-headless\", \"quorumPort\": 36463, \"electionPort\": 36464}"""
  if [[ ! -z "$server" ]]
  then
    server=$server","
  fi
  server=$server$conf
done
sed -i 's#${SERVERS}#'"$server"'#g' "$config_file"
mv $config_file /opt/centraldogma/conf/dogma.json
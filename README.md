## xitrum-metrics-demo

### Prototype of [Xitrum Metrics feature](https://github.com/ngocdaothanh/xitrum/issues/80)


* Start Project


    git clone https://github.com/georgeOsdDev/xitrum-metrics-demo.git
    sbt/sbt run


* Access with Browser

    open http://localhost:8000/xitrum/metrics/viewer

* Do this command in Broeser Develper Tool console.

    initMetricsChannel(function(){console.log(JSON.parse(arguments[0]))},function(){console.log(arguments)});


## TODO

* Modularize in xitrum
* Visual viewer
* Metrics sampling for action, actor, sockjs, etc
* Configlation setting
* Cluster Usage on glokka
* Code refactoring wiht Ngoc

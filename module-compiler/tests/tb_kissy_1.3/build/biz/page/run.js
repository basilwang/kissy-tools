/*
 Combined modules by KISSY Module Compiler: 

 biz/x
 biz/y
 biz/page/run
*/

KISSY.add("biz/x", function() {
    return "中文";
}, {requires:["overlay", "switchable",window.x>1?"my":"my2"]});

KISSY.add("biz/y", function() {
}, {requires:["./x"]});

KISSY.add("biz/page/run", function() {
}, {requires:["../y"]});



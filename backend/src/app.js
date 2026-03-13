const express = require('express');
const app = express();

app.get("/", (req, res)=>{
    console.log(res);
    
})

app.listen(3000, (err)=>{
    if(err)
        console.log("Error While starting the server");
    else
        console.log("Server running in http://localhost:3000");
})
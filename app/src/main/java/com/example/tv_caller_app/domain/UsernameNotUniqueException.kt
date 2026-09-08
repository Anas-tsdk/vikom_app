package com.example.tv_caller_app.domain

class UsernameNotUniqueException(username: String) :
    Exception("Brukernavnet \"$username\" er allerede i bruk. Velg et annet.")

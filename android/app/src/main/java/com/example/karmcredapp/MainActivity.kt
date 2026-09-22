override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_dashboard)

    etUserId = findViewById(R.id.etUserId)
    btnAnalyze = findViewById(R.id.btnAnalyze)
    tvScore = findViewById(R.id.tvScore)
    rvReasonCards = findViewById(R.id.rvReasonCards)

    // Set LayoutManager to prevent RecyclerView layout crashes
    rvReasonCards.layoutManager = LinearLayoutManager(this)

    btnAnalyze.setOnClickListener {
        val userId = etUserId.text.toString().trim()
        if (userId.isNotEmpty()) {
            fetchUserData(userId)
        } else {
            Toast.makeText(this, "Please enter a User ID", Toast.LENGTH_SHORT).show()
        }
    }
}
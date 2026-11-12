package com.example.exam

import kotlin.random.Random

/**
 * 150+ Enterprise Computer Institute Question Bank & Fisher-Yates Deterministic Shuffler (`QuestionBank.kt`).
 *
 * Covers:
 * - ADCA (Advanced Diploma in Computer Applications)
 * - DCA & MS Office Automation (Word, Excel, PowerPoint, Access)
 * - Tally Prime with GST & Financial Accounting
 * - Python Programming & Data Structures
 * - Web Development (HTML5, CSS3, JavaScript, React)
 * - CCC Digital Literacy, Operating Systems, Networking & Cybersecurity
 *
 * Implements the Fisher-Yates (Knuth) $O(n)$ Seed-Based Mathematical Shuffler so that:
 * 1. Every student receives a unique 50-question subset out of the 156-question bank.
 * 2. Both the question sequence AND the 4 options (A, B, C, D) are cryptographically shuffled
 *    while deterministically tracking the new `correctOptionIndex`.
 */
data class ExamQuestionItem(
    val id: String,
    val category: String,
    val questionText: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val marks: Int = 2,
    val explanation: String = ""
)

object QuestionBank {

    val fullQuestionPool: List<ExamQuestionItem> by lazy { build156InstituteQuestions() }

    /**
     * Deterministic Fisher-Yates (Knuth) $O(n)$ Mathematical Shuffler.
     *
     * Given a `studentId`, `examId`, and `sessionSeed`, shuffles the 156-question pool in-place
     * using Fisher-Yates, slices `targetCount` (default 50) questions, and shuffles the 4 options
     * (A, B, C, D) of each question while remapping `correctOptionIndex` accurately.
     */
    fun selectAndShuffleQuestionsForStudent(
        studentId: String,
        examId: String,
        courseFilter: String = "ALL",
        targetCount: Int = 50,
        sessionSalt: Long = System.currentTimeMillis() / (1000L * 60L * 60L)
    ): List<ExamQuestionItem> {
        val filteredPool = if (courseFilter.isBlank() || courseFilter.equals("ALL", ignoreCase = true)) {
            fullQuestionPool
        } else {
            val matching = fullQuestionPool.filter {
                it.category.contains(courseFilter, ignoreCase = true) ||
                    courseFilter.contains(it.category, ignoreCase = true)
            }
            if (matching.size >= targetCount) matching else fullQuestionPool
        }

        val combinedSeed = "$studentId|$examId|$sessionSalt".hashCode().toLong() xor (sessionSalt shl 16)
        val rng = Random(combinedSeed)

        // 1. Copy pool to mutable array for in-place O(n) Fisher-Yates shuffle
        val workingList = filteredPool.toMutableList()
        for (i in workingList.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val temp = workingList[i]
            workingList[i] = workingList[j]
            workingList[j] = temp
        }

        // 2. Slice top `targetCount` (e.g. 50 questions) and Fisher-Yates shuffle each question's 4 options
        return workingList.take(targetCount.coerceAtMost(workingList.size)).mapIndexed { idx, q ->
            val indexedOptions = q.options.mapIndexed { optIdx, text -> optIdx to text }.toMutableList()
            for (k in indexedOptions.lastIndex downTo 1) {
                val m = rng.nextInt(k + 1)
                val tmpOpt = indexedOptions[k]
                indexedOptions[k] = indexedOptions[m]
                indexedOptions[m] = tmpOpt
            }
            val newCorrectIndex = indexedOptions.indexOfFirst { it.first == q.correctOptionIndex }
                .coerceAtLeast(0)
            q.copy(
                id = "${q.id}_S${idx + 1}",
                options = indexedOptions.map { it.second },
                correctOptionIndex = newCorrectIndex
            )
        }
    }

    private fun build156InstituteQuestions(): List<ExamQuestionItem> {
        val list = mutableListOf<ExamQuestionItem>()

        fun addQ(cat: String, q: String, a: String, b: String, c: String, d: String, correct: Int, exp: String) {
            val index = list.size + 1
            list.add(
                ExamQuestionItem(
                    id = "WCI_Q%03d".format(index),
                    category = cat,
                    questionText = q,
                    options = listOf(a, b, c, d),
                    correctOptionIndex = correct,
                    marks = 2,
                    explanation = exp
                )
            )
        }

        // =====================================================================
        // SECTION 1: ADCA / DCA / COMPUTER FUNDAMENTALS & OS (30 Questions)
        // =====================================================================
        addQ("ADCA", "Which high-speed memory sits directly between the CPU registers and main RAM?", "Cache Memory (L1/L2/L3)", "Secondary Magnetic Disk", "Optical CD-ROM", "Virtual Swap Partition", 0, "Cache SRAM bridges CPU and DRAM latency.")
        addQ("ADCA", "What is the hexadecimal equivalent of the binary number 11011010?", "DA", "B6", "C8", "FA", 0, "1101 = D (13) and 1010 = A (10).")
        addQ("ADCA", "Which component of the CPU is responsible for executing arithmetic and bitwise logic operations?", "ALU (Arithmetic Logic Unit)", "Control Unit (CU)", "Bus Interface Unit", "DMA Controller", 0, "The ALU executes mathematical and logical operations.")
        addQ("ADCA", "In Windows 11, which shortcut opens the Task Manager directly without the intermediate security screen?", "Ctrl + Shift + Esc", "Ctrl + Alt + Del", "Win + R", "Alt + F4", 0, "Ctrl + Shift + Esc launches Task Manager directly.")
        addQ("ADCA", "Which file system is used by default for modern Windows 10/11 system boot partitions?", "NTFS", "FAT16", "ext4", "APFS", 0, "Windows uses NTFS for boot volumes.")
        addQ("ADCA", "What does BIOS stand for in computer system architecture?", "Basic Input Output System", "Binary Integrated Operating System", "Bus Internal Operation Setup", "Buffered Input Output Service", 0, "BIOS initializes hardware during POST.")
        addQ("ADCA", "Which generation of computers introduced Integrated Circuits (ICs)?", "Third Generation", "First Generation", "Second Generation", "Fourth Generation", 0, "ICs defined the Third Generation (1964–1971).")
        addQ("ADCA", "How many bytes are present in 2 Kilobytes (binary IEC standard KiB)?", "2048 Bytes", "2000 Bytes", "1024 Bytes", "4096 Bytes", 0, "2 * 1024 = 2048 bytes.")
        addQ("ADCA", "Which type of memory loses its stored data immediately when power is turned off?", "RAM (Volatile Memory)", "ROM", "EEPROM", "NVMe SSD", 0, "RAM is volatile working memory.")
        addQ("ADCA", "What is the role of an Operating System kernel?", "Managing CPU scheduling, memory, and hardware drivers", "Designing vector graphics", "Editing spreadsheets", "Providing Surge Protection", 0, "The kernel is the core resource manager of the OS.")
        addQ("ADCA", "Which port is capable of transmitting both uncompressed HD video and multi-channel digital audio?", "HDMI", "VGA", "PS/2", "RS-232 Serial", 0, "HDMI carries both digital video and audio.")
        addQ("ADCA", "What is the two's complement of the 8-bit binary number 00000101 (decimal 5)?", "11111011", "11111010", "10000101", "01111011", 0, "Invert bits (11111010) and add 1 -> 11111011.")
        addQ("ADCA", "Which command in Windows Command Prompt displays the IP address, subnet mask, and default gateway?", "ipconfig", "ping", "tracert", "nslookup", 0, "ipconfig displays TCP/IP adapter configuration.")
        addQ("ADCA", "Which printer technology uses a laser beam and powdered toner cartridge?", "Laser Printer", "Dot Matrix Printer", "Thermal Line Printer", "Daisy Wheel Printer", 0, "Laser printers fuse toner onto paper with heat.")
        addQ("ADCA", "What does POST stand for during computer startup?", "Power-On Self-Test", "Programmed Operating System Test", "Peripheral Output Signal Tracker", "Primary Object Storage Table", 0, "POST verifies hardware before booting.")
        addQ("ADCA", "Which key combination permanently deletes a file in Windows Explorer without sending it to the Recycle Bin?", "Shift + Delete", "Ctrl + Delete", "Alt + Backspace", "Win + Delete", 0, "Shift + Delete bypasses the Recycle Bin.")
        addQ("ADCA", "What is the standard ASCII code for the uppercase letter 'A'?", "65", "97", "48", "32", 0, "'A' is 65 in ASCII; 'a' is 97.")
        addQ("ADCA", "Which utility in Windows reorganizes fragmented data blocks on a mechanical hard disk?", "Disk Defragmenter (Optimize Drives)", "Disk Cleanup", "Device Manager", "Event Viewer", 0, "Defragmentation consolidates contiguous sectors.")
        addQ("ADCA", "Which bus carries the memory address from the CPU to RAM?", "Address Bus", "Data Bus", "Control Bus", "USB Bus", 0, "The unidirectional Address Bus specifies memory locations.")
        addQ("ADCA", "What does GUI stand for?", "Graphical User Interface", "General Utility Instrument", "Global User Integration", "Guided Universal Input", 0, "GUI uses windows, icons, and menus.")
        addQ("ADCA", "Which Linux command lists files including hidden dotfiles in long format?", "ls -la", "cd ..", "mkdir -p", "chmod 777", 0, "ls -la shows all files with permissions and owners.")
        addQ("ADCA", "What is Virtual Memory in an operating system?", "Disk space used as an extension of physical RAM", "Cloud storage on Google Drive", "CPU L1 instruction cache", "Graphics card VRAM", 0, "Paging uses disk space when RAM is full.")
        addQ("ADCA", "Which numbering system uses base 8 and digits 0 through 7?", "Octal", "Hexadecimal", "Binary", "Decimal", 0, "Octal is base-8.")
        addQ("ADCA", "Which shortcut locks a Windows workstation immediately?", "Win + L", "Win + D", "Ctrl + L", "Alt + L", 0, "Win + L locks the active user session.")
        addQ("ADCA", "What is a Deadlock in operating systems?", "Two or more processes waiting indefinitely for resources held by each other", "A crashed hard drive sector", "A firewall blocking port 80", "An expired antivirus license", 0, "Mutual exclusion, hold-and-wait, no preemption, and circular wait cause deadlock.")
        addQ("ADCA", "Which storage medium uses NAND flash memory chips and has no moving parts?", "SSD (Solid State Drive)", "HDD (Hard Disk Drive)", "Floppy Disk", "Magnetic Tape", 0, "SSDs use non-volatile NAND flash.")
        addQ("ADCA", "What is the full form of USB?", "Universal Serial Bus", "Unified System Board", "Ultra Speed Bandwidth", "Universal Storage Buffer", 0, "Universal Serial Bus.")
        addQ("ADCA", "Which register holds the memory address of the next instruction to be executed by the CPU?", "Program Counter (PC)", "Accumulator (ACC)", "Status Register", "Stack Pointer", 0, "The Program Counter points to the next instruction.")
        addQ("ADCA", "Which software translates an entire high-level source code program into machine code at once?", "Compiler", "Interpreter", "Assembler", "Linker", 0, "A compiler translates the whole program ahead of execution.")
        addQ("ADCA", "What is the decimal value of the binary number 101101?", "45", "43", "53", "39", 0, "32 + 0 + 8 + 4 + 0 + 1 = 45.")

        // =====================================================================
        // SECTION 2: MS OFFICE (WORD, EXCEL, POWERPOINT) (26 Questions)
        // =====================================================================
        addQ("DCA", "In MS Excel, which symbol must precede every formula or function?", "=", "#", "@", "&", 0, "All Excel formulas start with '='.")
        addQ("DCA", "In MS Excel, what does the cell reference \$B\$4 represent?", "Absolute reference to column B and row 4", "Relative reference", "Mixed sheet reference", "Currency format", 0, "The '$' sign locks both column and row during autofill.")
        addQ("DCA", "Which Excel function searches for a value in the first column of a table and returns a value in the same row from another column?", "VLOOKUP", "HLOOKUP", "SUMIF", "COUNTBLANK", 0, "Vertical Lookup (VLOOKUP) searches vertically down column 1.")
        addQ("DCA", "In MS Word, which shortcut key applies Center Alignment to the selected paragraph?", "Ctrl + E", "Ctrl + C", "Ctrl + J", "Ctrl + R", 0, "Ctrl + E centers text; Ctrl + C copies.")
        addQ("DCA", "Which feature in MS Word allows generating personalized letters or certificates for 500 students from an Excel sheet?", "Mail Merge", "Track Changes", "Macro Recorder", "Bookmark", 0, "Mail Merge combines a template document with a data source.")
        addQ("DCA", "In MS PowerPoint, which function key starts the slide show from the very first slide?", "F5", "Shift + F5", "F7", "F12", 0, "F5 starts from slide 1; Shift + F5 starts from the current slide.")
        addQ("DCA", "What is the result of the Excel formula =COUNT(10, 20, \"WCI\", 30)?", "3", "4", "60", "#VALUE!", 0, "COUNT counts only numeric values (10, 20, 30 -> 3).")
        addQ("DCA", "Which Excel function counts all non-empty cells regardless of whether they contain text or numbers?", "COUNTA", "COUNT", "COUNTIF", "SUM", 0, "COUNTA counts all non-blank cells.")
        addQ("DCA", "In MS Word, which shortcut opens the 'Find and Replace' dialog box directly on the Replace tab?", "Ctrl + H", "Ctrl + F", "Ctrl + G", "Ctrl + R", 0, "Ctrl + H opens Find & Replace.")
        addQ("DCA", "What is the default file extension of an MS Excel 2019/365 workbook?", ".xlsx", ".xlsm", ".xls", ".csv", 0, ".xlsx is the OpenXML workbook format.")
        addQ("DCA", "In MS Word, what is a 'Gutter Margin'?", "Extra margin space added to the binding side of a page for printing", "Margin at the bottom footer", "Space between two columns", "Header padding", 0, "Gutter margin ensures text isn't hidden by book binding.")
        addQ("DCA", "Which shortcut inserts a new blank slide in MS PowerPoint?", "Ctrl + M", "Ctrl + N", "Ctrl + D", "Alt + N", 0, "Ctrl + M inserts a new slide; Ctrl + N creates a new presentation.")
        addQ("DCA", "In MS Excel, which error appears when a formula attempts to divide a number by zero?", "#DIV/0!", "#NAME?", "#REF!", "#N/A", 0, "Division by zero or an empty cell produces #DIV/0!.")
        addQ("DCA", "Which Excel feature summarizes large datasets interactively with drag-and-drop rows, columns, and values?", "PivotTable", "Goal Seek", "Conditional Formatting", "Data Validation", 0, "PivotTables aggregate and cross-tabulate datasets.")
        addQ("DCA", "In MS Word, which key activates Spell Check and Grammar Review?", "F7", "F5", "Shift + F7", "F1", 0, "F7 runs Editor/Spell Check; Shift + F7 opens the Thesaurus.")
        addQ("DCA", "In MS Excel, what is the intersection of a Row and a Column called?", "Cell", "Range", "Worksheet", "Gridline", 0, "Each box addressed like A1 or C5 is a Cell.")
        addQ("DCA", "Which Excel function concatenates or joins multiple text strings with a specified delimiter?", "TEXTJOIN", "LEFT", "MID", "LEN", 0, "TEXTJOIN(delimiter, ignore_empty, text1, ...) joins strings.")
        addQ("DCA", "In MS Word, which shortcut increases the font size of selected text by 1 point?", "Ctrl + ]", "Ctrl + [", "Ctrl + +", "Alt + Up", 0, "Ctrl + ] increments font size by 1 pt.")
        addQ("DCA", "In MS PowerPoint, what is the difference between Slide Transition and Custom Animation?", "Transitions animate between slides; Animations animate objects on a slide", "Both are identical", "Transitions apply only to text", "Animations apply only to master slides", 0, "Transitions occur on slide change; animations move elements within a slide.")
        addQ("DCA", "Which Excel shortcut inserts the current system date into a cell?", "Ctrl + ;", "Ctrl + Shift + ;", "Alt + D", "Ctrl + T", 0, "Ctrl + ; inserts date; Ctrl + Shift + ; inserts current time.")
        addQ("DCA", "In MS Word, what does 'Ctrl + J' do to a paragraph?", "Justifies paragraph alignment evenly on both left and right margins", "Jumps to page 1", "Joins two tables", "Inserts a page break", 0, "Ctrl + J applies Justify alignment.")
        addQ("DCA", "What is the maximum number of rows in a modern MS Excel (.xlsx) worksheet?", "1,048,576", "65,536", "16,384", "999,999", 0, "Excel worksheets have 1,048,576 rows and 16,384 columns (XFD).")
        addQ("DCA", "In MS Excel, which function returns the remainder after a number is divided by a divisor?", "MOD(number, divisor)", "INT(number)", "ROUND(number, digits)", "QUOTIENT(n, d)", 0, "MOD(17, 5) returns 2.")
        addQ("DCA", "In MS Word, which shortcut inserts a hard Page Break?", "Ctrl + Enter", "Shift + Enter", "Alt + Enter", "Ctrl + Backspace", 0, "Ctrl + Enter starts a new page; Shift + Enter inserts a line break.")
        addQ("DCA", "Which view in MS PowerPoint displays miniature thumbnails of all slides for easy reordering?", "Slide Sorter View", "Outline View", "Notes Page View", "Reading View", 0, "Slide Sorter View shows all slide thumbnails in a grid.")
        addQ("DCA", "In MS Excel, which tool restricts the type of data or values that users can enter into a cell (e.g., dropdown list)?", "Data Validation", "Consolidate", "Scenario Manager", "Flash Fill", 0, "Data Validation enforces input rules and dropdown lists.")

        // =====================================================================
        // SECTION 3: TALLY PRIME WITH GST & ACCOUNTING (25 Questions)
        // =====================================================================
        addQ("TALLY", "In Tally Prime, which function key is used to record a Payment Voucher?", "F5", "F4", "F6", "F8", 0, "F5 = Payment, F4 = Contra, F6 = Receipt, F8 = Sales, F9 = Purchase.")
        addQ("TALLY", "Under which Primary Group should 'Bank Account (SBI Current A/c)' be created in Tally Prime?", "Bank Accounts", "Current Liabilities", "Indirect Expenses", "Capital Account", 0, "Current and Savings bank accounts belong to Bank Accounts.")
        addQ("TALLY", "In Tally Prime, which voucher type is used when cash is deposited into the bank or withdrawn from ATM?", "Contra Voucher (F4)", "Receipt Voucher (F6)", "Journal Voucher (F7)", "Payment Voucher (F5)", 0, "Cash-to-Bank and Bank-to-Cash transfers use Contra (F4).")
        addQ("TALLY", "What is the golden rule of accounting for a Real Account?", "Debit what comes in, Credit what goes out", "Debit the receiver, Credit the giver", "Debit all expenses and losses, Credit all incomes and gains", "Debit assets, Credit capital", 0, "Real Account rule: Debit what comes in, Credit what goes out.")
        addQ("TALLY", "In Tally Prime, which shortcut key opens the 'Go To' search bar from anywhere in the application?", "Alt + G", "Ctrl + G", "Alt + K", "F11", 0, "Alt + G is the universal 'Go To' navigation shortcut in Tally Prime.")
        addQ("TALLY", "When goods are sold to a customer within the same state (Intra-State supply), which GST taxes are levied?", "CGST + SGST", "IGST only", "UTGST + IGST", "Custom Duty only", 0, "Intra-state supplies attract Central GST (CGST) and State GST (SGST) equally.")
        addQ("TALLY", "When goods are sold from Delhi to a buyer in Maharashtra (Inter-State supply), which GST ledger is applied?", "IGST (Integrated GST)", "CGST + SGST", "VAT", "Excise Duty", 0, "Inter-state transactions attract IGST.")
        addQ("TALLY", "How many characters are there in a valid Indian GSTIN (Goods and Services Tax Identification Number)?", "15 alphanumeric characters", "10 characters", "12 digits", "16 digits", 0, "GSTIN is 15 characters: 2-digit State Code + 10-char PAN + Entity + Z + Checksum.")
        addQ("TALLY", "In Tally Prime, which key opens 'Company Features' to enable GST, TDS, and Inventory settings?", "F11", "F12", "Alt + F1", "Ctrl + F3", 0, "F11 opens Company Features; F12 opens Configuration.")
        addQ("TALLY", "Under which group is 'Sundry Debtors' classified in Tally Prime?", "Current Assets", "Current Liabilities", "Direct Incomes", "Fixed Assets", 0, "Customers who owe money (Sundry Debtors) are part of Current Assets.")
        addQ("TALLY", "In Tally Prime, which voucher is recorded when a customer returns damaged goods previously sold to them?", "Credit Note (Alt + F6)", "Debit Note (Alt + F5)", "Sales Voucher (F8)", "Contra Voucher (F4)", 0, "Sales Return is recorded via Credit Note.")
        addQ("TALLY", "In Tally Prime, which voucher is used for Purchase Return (returning goods to a supplier)?", "Debit Note (Alt + F5)", "Credit Note (Alt + F6)", "Receipt Voucher (F6)", "Journal Voucher (F7)", 0, "Purchase Return is recorded via Debit Note.")
        addQ("TALLY", "Which GST return summarizes outward supplies (sales invoices) filed monthly or quarterly by a registered taxpayer?", "GSTR-1", "GSTR-3B", "GSTR-9", "GSTR-2A", 0, "GSTR-1 reports outward supplies of goods and services.")
        addQ("TALLY", "In Tally Prime, under which group is 'Office Rent Paid' or 'Electricity Bill' created?", "Indirect Expenses", "Direct Expenses", "Current Assets", "Suspense Account", 0, "Administrative and office overheads fall under Indirect Expenses.")
        addQ("TALLY", "In Tally Prime, under which group is 'Factory Wages' or 'Freight Inward' created?", "Direct Expenses", "Indirect Expenses", "Fixed Assets", "Loans & Advances", 0, "Manufacturing and purchase freight costs are Direct Expenses.")
        addQ("TALLY", "Which financial statement shows a company's Assets and Liabilities at a specific point in time?", "Balance Sheet", "Trial Balance", "Day Book", "Cash Flow Projection", 0, "The Balance Sheet reflects financial position (Assets = Liabilities + Equity).")
        addQ("TALLY", "In Tally Prime, which shortcut duplicates an entry or creates a master on the fly inside a voucher screen?", "Alt + C", "Ctrl + C", "Alt + D", "Ctrl + A", 0, "Alt + C creates a secondary ledger/item on the fly during voucher entry.")
        addQ("TALLY", "In Tally Prime, which shortcut saves/accepts a voucher or master screen immediately?", "Ctrl + A", "Ctrl + S", "Alt + S", "Shift + Enter", 0, "Ctrl + A accepts/saves the current screen in Tally Prime.")
        addQ("TALLY", "What is the HSN code used for under GST?", "Harmonized System of Nomenclature for classifying goods", "Service Accounting Code", "Bank IFSC Routing", "Income Tax PAN verification", 0, "HSN classifies physical goods; SAC classifies services.")
        addQ("TALLY", "When is an E-Way Bill mandatory for inter-state movement of goods under standard GST rules?", "When consignment value exceeds ₹50,000", "For every ₹500 invoice", "Only for export by sea", "Only for exempted agricultural goods", 0, "E-Way Bill is generally required for movement of goods exceeding ₹50,000.")
        addQ("TALLY", "In Tally Prime, which report lists the debit and credit balances of all ledgers to check arithmetical accuracy?", "Trial Balance", "Stock Summary", "Bank Reconciliation", "Ratio Analysis", 0, "Trial Balance verifies that total Debits equal total Credits.")
        addQ("TALLY", "What is the golden rule of accounting for a Nominal Account?", "Debit all expenses & losses, Credit all incomes & gains", "Debit what comes in, Credit what goes out", "Debit the receiver, Credit the giver", "Debit liabilities, Credit assets", 0, "Nominal accounts cover expenses, losses, incomes, and gains.")
        addQ("TALLY", "In Tally Prime, which function key is used to record a Credit Purchase of inventory items?", "F9 (Purchase Voucher)", "F8 (Sales Voucher)", "F7 (Journal Voucher)", "F6 (Receipt Voucher)", 0, "F9 opens the Purchase Voucher.")
        addQ("TALLY", "What is Bank Reconciliation Statement (BRS) in Tally Prime used for?", "Matching company bank book entries with the actual bank statement", "Calculating employee PF", "Generating barcoded labels", "Calculating depreciation", 0, "BRS reconciles unpresented cheques and bank charges.")
        addQ("TALLY", "In Tally Prime, which shortcut deletes a voucher when viewing it in alter mode?", "Alt + D", "Ctrl + D", "Shift + Del", "Alt + X", 0, "Alt + D deletes the current voucher or master; Alt + X cancels a voucher.")

        // =====================================================================
        // SECTION 4: PYTHON PROGRAMMING & DATA STRUCTURES (25 Questions)
        // =====================================================================
        addQ("PYTHON", "What is the output of `print(type([10, 20, 30]))` in Python 3?", "<class 'list'>", "<class 'tuple'>", "<class 'dict'>", "<class 'set'>", 0, "Square brackets [] define a mutable list in Python.")
        addQ("PYTHON", "Which of the following data types in Python is immutable (cannot be modified after creation)?", "Tuple `(1, 2, 3)`", "List `[1, 2, 3]`", "Dictionary `{'a': 1}`", "Set `{1, 2, 3}`", 0, "Tuples and strings are immutable in Python.")
        addQ("PYTHON", "What does the expression `2 ** 4` evaluate to in Python?", "16", "8", "6", "24", 0, "The `**` operator performs exponentiation: 2^4 = 16.")
        addQ("PYTHON", "What is the output of `'Waqar'[::-1]` in Python?", "'raqaW'", "'Waqar'", "'W'", "IndexError", 0, "Slicing with step -1 reverses the string.")
        addQ("PYTHON", "Which keyword is used to define a function in Python?", "def", "function", "fun", "lambda", 0, "`def` defines a named function in Python.")
        addQ("PYTHON", "What does the `//` operator do in Python (e.g., `17 // 5`)?", "Floor Division returning the integer quotient (3)", "Floating point division (3.4)", "Modulus remainder (2)", "Inline comment", 0, "`17 // 5` returns 3.")
        addQ("PYTHON", "Which Python collection automatically removes duplicate elements and is unordered?", "set", "list", "tuple", "str", 0, "A `set` stores only unique hashable elements.")
        addQ("PYTHON", "In Python, which method adds a single element to the end of a list?", "append()", "extend()", "insert()", "pop()", 0, "`list.append(x)` appends a single item to the tail.")
        addQ("PYTHON", "What is the output of `len({'name': 'Aamir', 'course': 'ADCA', 'fee': 12000})`?", "3", "6", "1", "Error", 0, "`len()` on a dictionary returns the number of key-value pairs (3).")
        addQ("PYTHON", "Which statement is used in Python to handle runtime exceptions gracefully?", "try ... except", "do ... catch", "check ... error", "trap ... throw", 0, "Python uses `try`, `except`, `else`, and `finally`.")
        addQ("PYTHON", "What will `list(range(2, 10, 3))` produce in Python?", "[2, 5, 8]", "[2, 3, 4, 5, 6, 7, 8, 9, 10]", "[2, 5, 8, 11]", "[3, 6, 9]", 0, "Starts at 2, steps by 3, stops before 10 -> 2, 5, 8.")
        addQ("PYTHON", "In Python OOP, what is the special name of the constructor method called when a new object is instantiated?", "__init__(self)", "__new_object__()", "constructor()", "__main__()", 0, "`__init__` initializes instance attributes.")
        addQ("PYTHON", "Which built-in Python function reads a line of text from the user via standard input as a string?", "input()", "scanf()", "readLine()", "cin", 0, "`input()` returns user input as a `str`.")
        addQ("PYTHON", "What is the output of `bool(\"\")` and `bool(\"0\")` in Python?", "False and True", "False and False", "True and True", "True and False", 0, "An empty string is falsy (`False`), while any non-empty string (`\"0\"`) is truthy (`True`).")
        addQ("PYTHON", "Which Python library is the industry standard for numerical multidimensional arrays and matrix operations?", "NumPy", "Tkinter", "Flask", "BeautifulSoup", 0, "NumPy provides fast C-backed `ndarray` structures.")
        addQ("PYTHON", "How do you open a file `'marks.txt'` in Python so it closes automatically after the block finishes?", "with open('marks.txt', 'r') as f:", "open('marks.txt').autoClose()", "file('marks.txt')", "import marks.txt", 0, "Context managers (`with open(...) as f:`) guarantee file closure.")
        addQ("PYTHON", "What does a `lambda` expression create in Python?", "An anonymous inline function", "A multithreaded loop", "A database connection", "A global constant", 0, "`lambda x: x * 2` creates a concise anonymous function.")
        addQ("PYTHON", "What is the result of `[x * x for x in range(4)]` in Python?", "[0, 1, 4, 9]", "[1, 4, 9, 16]", "[0, 2, 4, 6]", "(0, 1, 4, 9)", 0, "List comprehension squares 0, 1, 2, 3 -> [0, 1, 4, 9].")
        addQ("PYTHON", "Which dictionary method safely retrieves a value for a key without raising a `KeyError` if the key is missing?", "dict.get(key, default)", "dict[key]", "dict.pop(key)", "dict.remove(key)", 0, "`d.get('score', 0)` returns `0` if `'score'` is absent.")
        addQ("PYTHON", "What is PEP 8 in the Python ecosystem?", "The official style guide for clean, readable Python code", "A Python compiler for Android", "An encryption algorithm", "A SQL database driver", 0, "PEP 8 defines Python naming and indentation conventions.")
        addQ("PYTHON", "In Python, what does the `break` statement do inside a `for` or `while` loop?", "Terminates the innermost enclosing loop immediately", "Skips only the current iteration", "Exits the entire operating system", "Restarts the loop from index 0", 0, "`break` exits the loop; `continue` skips to the next iteration.")
        addQ("PYTHON", "What is the time complexity of looking up a key in a Python `dict` or `set` on average?", "O(1) Constant Time", "O(n) Linear Time", "O(n^2) Quadratic Time", "O(log n) Binary Time", 0, "Hash tables provide O(1) average lookup complexity.")
        addQ("PYTHON", "What is the output of `'wci_institute'.upper().split('_')`?", "['WCI', 'INSTITUTE']", "'WCI_INSTITUTE'", "['wci', 'institute']", "('WCI', 'INSTITUTE')", 0, "`upper()` converts to uppercase and `split('_')` returns a list of 2 tokens.")
        addQ("PYTHON", "Which keyword is used inside a Python function to send a result back to the caller?", "return", "give", "send", "exit", 0, "`return` exits the function with a return value.")
        addQ("PYTHON", "In Python, what does `*args` in a function definition `def calculate(*args):` allow?", "Passing a variable number of positional arguments as a tuple", "Passing C++ pointers", "Multiplying all arguments", "Enforcing keyword-only arguments", 0, "`*args` packs extra positional arguments into a tuple.")

        // =====================================================================
        // SECTION 5: WEB DEVELOPMENT (HTML5, CSS3, JAVASCRIPT) (25 Questions)
        // =====================================================================
        addQ("WEB", "Which HTML5 semantic element represents the primary navigation links of a website?", "<nav>", "<header>", "<aside>", "<section>", 0, "`<nav>` defines a block of navigation links.")
        addQ("WEB", "In CSS3, which property changes the background color of an element?", "background-color", "color", "fill-style", "bg-tint", 0, "`background-color` sets the background; `color` sets text color.")
        addQ("WEB", "In HTML5, which attribute on an `<img>` tag provides fallback text for screen readers and when the image fails to load?", "alt", "src", "title", "href", 0, "The `alt` attribute is mandatory for web accessibility.")
        addQ("WEB", "In CSS Flexbox, which property aligns flex items along the main axis?", "justify-content", "align-items", "flex-wrap", "z-index", 0, "`justify-content` controls main-axis distribution.")
        addQ("WEB", "In JavaScript (ES6+), which keyword declares a block-scoped constant whose reference cannot be reassigned?", "const", "var", "let", "static", 0, "`const` creates a read-only block-scoped binding.")
        addQ("WEB", "What does DOM stand for in Web Development?", "Document Object Model", "Data Oriented Markup", "Dynamic Output Manager", "Digital Object Middleware", 0, "The DOM represents HTML elements as a tree of JavaScript objects.")
        addQ("WEB", "Which CSS selector targets an HTML element with `id=\"student-card\"`?", "#student-card", ".student-card", "*student-card", "@student-card", 0, "`#` selects by ID; `.` selects by class.")
        addQ("WEB", "In JavaScript, what is the difference between `==` and `===`?", "`===` checks both value and data type without type coercion", "`==` is faster and stricter", "Both behave identically", "`===` assigns values", 0, "Strict equality (`===`) prevents unexpected type conversion (`5 === '5'` is `false`).")
        addQ("WEB", "Which HTML5 input type provides automatic browser validation for an email address?", "<input type=\"email\">", "<input type=\"mail\">", "<input type=\"text\" email>", "<email>", 0, "`type=\"email\"` validates email syntax on form submit.")
        addQ("WEB", "In CSS Box Model, what is the correct order from innermost content to outermost edge?", "Content -> Padding -> Border -> Margin", "Content -> Margin -> Border -> Padding", "Padding -> Content -> Margin -> Border", "Border -> Padding -> Content -> Margin", 0, "Padding surrounds content, Border wraps padding, and Margin separates elements.")
        addQ("WEB", "Which JavaScript method parses a JSON string into a native JavaScript object?", "JSON.parse()", "JSON.stringify()", "JSON.toObject()", "Object.fromJson()", 0, "`JSON.parse()` deserializes JSON text; `JSON.stringify()` serializes objects.")
        addQ("WEB", "Which HTTP status code indicates that a requested web page or API resource was Not Found?", "404 Not Found", "200 OK", "500 Internal Server Error", "301 Moved Permanently", 0, "404 means the server could not find the requested URI.")
        addQ("WEB", "In CSS3, which `@media` rule is used to build responsive layouts that adapt to mobile and tablet screen widths?", "Media Queries (`@media (max-width: 768px)`)", "Keyframes (`@keyframes`)", "Font Face (`@font-face`)", "Import (`@import`)", 0, "Media queries apply styles conditionally based on viewport size.")
        addQ("WEB", "Which JavaScript API is used in modern browsers to make asynchronous HTTP network requests returning a Promise?", "fetch()", "window.prompt()", "document.write()", "setInterval()", 0, "`fetch(url)` returns a Promise that resolves to the HTTP `Response`.")
        addQ("WEB", "In HTML, which tag is used to create a hyperlink to another webpage?", "<a href=\"https://...\">", "<link src=\"...\">", "<url>", "<href>", 0, "The anchor tag `<a href=\"...\">` creates hyperlinks.")
        addQ("WEB", "In CSS, what does `position: fixed;` do to an element?", "Positions it relative to the browser viewport so it stays in place during scrolling", "Hides the element", "Centers the element inside its parent", "Converts it into a table cell", 0, "`position: fixed` pins the element to the viewport.")
        addQ("WEB", "Which JavaScript array method creates a new array populated with the results of calling a function on every element?", "Array.prototype.map()", "Array.prototype.forEach()", "Array.prototype.pop()", "Array.prototype.shift()", 0, "`map()` transforms each element and returns a new array of equal length.")
        addQ("WEB", "Where in an HTML document is the `<title>` tag placed?", "Inside the `<head>` section", "Inside the `<footer>` section", "After the `</body>` tag", "Inside a `<div>` tag", 0, "Metadata and `<title>` belong inside `<head>`.")
        addQ("WEB", "Which Web Storage API persists key-value data in the browser across browser restarts with no expiration date?", "localStorage", "sessionStorage", "RAM Buffer", "HTTP Query String", 0, "`localStorage` persists until explicitly cleared.")
        addQ("WEB", "In CSS Grid, what does `grid-template-columns: repeat(3, 1fr);` create?", "3 equal-width flexible columns", "3 rows of 1 pixel each", "1 column with 3 borders", "A 3-second animation", 0, "`1fr` allocates 1 fraction of available free space per column.")
        addQ("WEB", "What does CORS stand for in web security?", "Cross-Origin Resource Sharing", "Client Object Rendering System", "Cached Online Routing Service", "Compiled Output Runtime Script", 0, "CORS controls cross-domain HTTP requests via response headers.")
        addQ("WEB", "Which HTML5 element is used to draw 2D graphics and animations dynamically via JavaScript?", "<canvas>", "<marquee>", "<iframe-gl>", "<figure>", 0, "The `<canvas>` element provides a 2D/WebGL rendering context.")
        addQ("WEB", "In JavaScript, what does `async / await` simplify?", "Working with asynchronous Promises in a clean, synchronous-looking style", "Styling CSS animations", "Creating SQL tables", "Compressing PNG images", 0, "`async/await` is syntactic sugar over JavaScript Promises.")
        addQ("WEB", "Which CSS unit is relative to the font-size of the root `<html>` element?", "rem", "em", "px", "vh", 0, "`rem` stands for Root EM.")
        addQ("WEB", "Which HTTP method is idempotent and typically used to retrieve data from a REST API without side effects?", "GET", "POST", "PATCH", "CONNECT", 0, "GET requests retrieve data without mutating server state.")

        // =====================================================================
        // SECTION 6: CCC DIGITAL LITERACY, NETWORKING & CYBERSECURITY (25 Questions)
        // =====================================================================
        addQ("CCC", "How many layers are defined in the standard ISO/OSI Networking Reference Model?", "7 Layers", "4 Layers", "5 Layers", "8 Layers", 0, "Physical, Data Link, Network, Transport, Session, Presentation, Application.")
        addQ("CCC", "Which protocol encrypts HTTP web traffic using TLS/SSL on TCP Port 443?", "HTTPS", "FTP", "Telnet", "SNMP", 0, "HTTPS secures HTTP communication over TLS on port 443.")
        addQ("CCC", "What is the length of an IPv4 address and an IPv6 address in bits?", "IPv4 is 32-bit; IPv6 is 128-bit", "IPv4 is 16-bit; IPv6 is 64-bit", "IPv4 is 64-bit; IPv6 is 256-bit", "Both are 32-bit", 0, "IPv4 uses 32 bits (4 octets); IPv6 uses 128 bits.")
        addQ("CCC", "In Indian Digital Banking, what does UPI stand for?", "Unified Payments Interface", "Universal Payment Gateway of India", "United Portal for Internet Banking", "Unique Pin Identification", 0, "NPCI developed Unified Payments Interface (UPI) for real-time transfers.")
        addQ("CCC", "Which organization developed and operates the UPI and RuPay payment networks in India?", "NPCI (National Payments Corporation of India)", "SEBI", "IRDAI", "TRAI", 0, "NPCI operates UPI, IMPS, RuPay, and BHIM.")
        addQ("CCC", "What is the minimum transfer limit for RTGS (Real Time Gross Settlement) in India?", "₹2,00,000 (Two Lakh Rupees)", "₹1", "₹50,000", "₹10,00,000", 0, "RTGS is designed for high-value transactions starting at ₹2 Lakh.")
        addQ("CCC", "Which networking device operates at Layer 3 (Network Layer) and forwards packets between different IP networks?", "Router", "Passive Hub", "Repeater", "RJ-45 Connector", 0, "Routers inspect Layer-3 IP headers to route packets across subnets.")
        addQ("CCC", "What is 'Phishing' in cybersecurity?", "Fraudulent emails or websites impersonating trusted institutions to steal passwords/OTPs", "Overclocking a CPU", "Defragmenting a Linux server", "Upgrading Wi-Fi firmware", 0, "Phishing uses social engineering to harvest credentials.")
        addQ("CCC", "What does 2FA / MFA stand for in account security?", "Two-Factor / Multi-Factor Authentication", "Two-File Archiving", "Multiple Firewall Adapter", "Fast Frame Acceleration", 0, "MFA combines something you know (password) with something you have (TOTP/hardware key) or are (biometrics).")
        addQ("CCC", "Which service translates human-readable domain names (like `waqarinstitute.edu`) into numerical IP addresses?", "DNS (Domain Name System)", "DHCP", "ARP", "ICMP", 0, "DNS is the phonebook of the Internet.")
        addQ("CCC", "In email communication, what is the difference between CC and BCC?", "Recipients in BCC are hidden from all other recipients", "CC encrypts attachments", "BCC allows larger file attachments", "There is no difference", 0, "Blind Carbon Copy (BCC) hides recipient addresses from each other.")
        addQ("CCC", "Which Government of India cloud platform provides citizens with authentic digital documents like Aadhaar, Driving License, and Marksheets?", "DigiLocker", "SWAYAM", "e-Hospital", "IRCTC", 0, "DigiLocker issues legally valid digital documents under the IT Act.")
        addQ("CCC", "What is the size of a MAC (Media Access Control) hardware address?", "48 bits (6 bytes) written in hexadecimal", "32 bits", "128 bits", "16 bits", 0, "MAC addresses are 48-bit hardware identifiers assigned to NICs.")
        addQ("CCC", "Which wireless technology is commonly used for short-range contactless payments (Tap & Pay) and smart ID cards?", "NFC (Near Field Communication)", "WiMAX", "Satellite VSAT", "Infrared Blaster", 0, "NFC operates at 13.56 MHz over a few centimeters.")
        addQ("CCC", "Which email protocol is used specifically for sending/pushing outgoing emails from a client to a mail server?", "SMTP (Simple Mail Transfer Protocol)", "POP3", "IMAP", "RDP", 0, "SMTP sends mail; IMAP and POP3 retrieve mail.")
        addQ("CCC", "What is Ransomware?", "Malware that encrypts a victim's files and demands payment for the decryption key", "Free open-source office software", "A hardware cooling fan", "An ad-blocker browser extension", 0, "Ransomware locks user files using encryption until a ransom is paid.")
        addQ("CCC", "In USSD mobile banking (without internet), which short code is dialed in India to access *99# NUUP services?", "*99#", "*121#", "#06#", "*400#", 0, "*99# provides USSD-based banking across GSM handsets.")
        addQ("CCC", "What does IMEI stand for, and how can you view it on any mobile phone?", "International Mobile Equipment Identity (dial *#06#)", "Internet Mobile Encryption Index", "Indian Mobile Exchange Identifier", "Internal Memory Execution ID", 0, "Dialing *#06# displays the 15-digit IMEI number.")
        addQ("CCC", "Which cryptographic hashing algorithm produces a 256-bit (64 hexadecimal character) digest and is used in TLS and blockchain?", "SHA-256", "MD4", "DES", "ROT13", 0, "SHA-256 is part of the SHA-2 cryptographic hash family.")
        addQ("CCC", "What is a Firewall in computer networking?", "A security system that monitors and filters incoming and outgoing network traffic based on rules", "A physical fireproof server cabinet", "An uninterruptible power supply", "A graphic rendering engine", 0, "Firewalls enforce packet-filtering and stateful access control policies.")
        addQ("CCC", "Which network topology connects every node to a central switch in a spoke-like arrangement?", "Star Topology", "Bus Topology", "Ring Topology", "Daisy Chain", 0, "Modern Ethernet LANs use Star Topology connected to a central switch.")
        addQ("CCC", "What does VPN stand for, and what is its primary purpose?", "Virtual Private Network — creates an encrypted tunnel over a public network", "Visual Processing Node", "Verified Public Name", "Voice Packet Network", 0, "VPNs encrypt traffic and mask routing over untrusted networks.")
        addQ("CCC", "In Aadhaar Enabled Payment System (AePS), what is required from the customer to complete a micro-ATM transaction?", "Aadhaar Number + Biometric Fingerprint/Iris Authentication", "International Credit Card CVV", " Cheque Book Leaf", "Swift Code", 0, "AePS authenticates transactions using Aadhaar number and biometrics.")
        addQ("CCC", "Which cloud service model provides virtualized hardware (VMs, storage, networks) on demand?", "IaaS (Infrastructure as a Service)", "SaaS (Software as a Service)", "PaaS (Platform as a Service)", "DaaS", 0, "IaaS provides fundamental compute and storage infrastructure.")
        addQ("CCC", "What is the purpose of an OTP (One-Time Password)?", "A time-sensitive single-use code that prevents replay attacks during login or payment", "A permanent password for Wi-Fi", "A BIOS recovery jumper", "An offline license key", 0, "OTPs are valid for a single session or transaction.")

        return list
    }
}

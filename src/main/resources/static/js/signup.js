document.getElementById('signupForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const msg  = document.getElementById('message');
    const btn  = document.getElementById('registerBtn');
    const spinner = document.getElementById('registerSpinner');

    // Client-side validation
    if (!form.checkValidity()) {
        form.querySelectorAll(':invalid').forEach(el => el.classList.add('is-invalid'));
        return;
    }
    form.querySelectorAll('.is-invalid').forEach(el => el.classList.remove('is-invalid'));

    const username = document.getElementById('username').value.trim();
    const email    = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;

    btn.disabled = true;
    spinner.classList.remove('d-none');
    msg.className = 'alert d-none';

    try {
        const response = await fetch('/api/auth/signup', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, email, password })
        });
        const data = await response.json();

        if (response.ok) {
            msg.className = 'alert alert-success';
            msg.textContent = '';
            msg.appendChild(document.createTextNode('Account created! '));
            const a = document.createElement('a');
            a.href = '/login';
            a.textContent = 'Sign in here';
            msg.appendChild(a);
            msg.classList.remove('d-none');
            form.reset();
        } else {
            msg.className = 'alert alert-danger';
            // Use textContent to prevent XSS from server error messages
            msg.textContent = data.error || 'Registration failed. Please try again.';
            msg.classList.remove('d-none');
        }
    } catch (err) {
        msg.className = 'alert alert-danger';
        msg.textContent = 'A network error occurred. Please try again.';
        msg.classList.remove('d-none');
    } finally {
        btn.disabled = false;
        spinner.classList.add('d-none');
    }
});

// Clear validation state on input
document.querySelectorAll('.form-control').forEach(input => {
    input.addEventListener('input', () => input.classList.remove('is-invalid'));
});
